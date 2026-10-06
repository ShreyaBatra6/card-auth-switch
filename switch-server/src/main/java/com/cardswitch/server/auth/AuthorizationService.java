package com.cardswitch.server.auth;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.iso8583.card.Luhn;
import com.cardswitch.server.core.Responses;
import com.cardswitch.server.metrics.SwitchMetrics;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerGateway;
import com.cardswitch.server.routing.IssuerOutcome;
import com.cardswitch.server.routing.IssuerRegistry;
import com.cardswitch.server.stip.StandInProcessor;
import com.cardswitch.server.stip.StoreAndForwardQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.AUTH_ID_RESPONSE;
import static com.cardswitch.iso8583.Field.CURRENCY_CODE;
import static com.cardswitch.iso8583.Field.EXPIRATION_DATE;
import static com.cardswitch.iso8583.Field.MERCHANT_ID;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.PROCESSING_CODE;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;
import static com.cardswitch.iso8583.Field.RRN;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;

/**
 * Handles 0100 authorization requests.
 * <p>
 * Pipeline, cheapest checks first; the first failure decides the response:
 * <ol>
 *   <li>Format: mandatory fields present, supported processing code, positive amount (30 / 12 / 13)</li>
 *   <li>Duplicate transmission (94)</li>
 *   <li>Luhn check digit (14)</li>
 *   <li>Expiry date (54)</li>
 *   <li>BIN routing to an issuer (15)</li>
 *   <li>Velocity limits (65 / 61)</li>
 *   <li>Issuer authorization, with stand-in processing if the issuer is slow or down</li>
 * </ol>
 * Steps 1-6 run inline on the network thread; step 7 is asynchronous, so no thread waits on an issuer.
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private static final int[] MANDATORY_FIELDS = {
            PAN, PROCESSING_CODE, AMOUNT, TRANSMISSION_DATE_TIME, STAN, EXPIRATION_DATE, RRN, TERMINAL_ID,
            MERCHANT_ID, CURRENCY_CODE
    };

    /** Processing code transaction types: 00 goods and services, 01 cash withdrawal. */
    private static final Set<String> SUPPORTED_TRANSACTION_TYPES = Set.of("00", "01");

    private final IssuerRegistry issuers;
    private final IssuerGateway gateway;
    private final StandInProcessor standIn;
    private final StoreAndForwardQueue storeAndForward;
    private final DuplicateDetector duplicates;
    private final VelocityTracker velocity;
    private final SwitchMetrics metrics;
    private final Clock clock;

    public AuthorizationService(IssuerRegistry issuers, IssuerGateway gateway, StandInProcessor standIn,
                                StoreAndForwardQueue storeAndForward, DuplicateDetector duplicates,
                                VelocityTracker velocity, SwitchMetrics metrics, Clock clock) {
        this.issuers = issuers;
        this.gateway = gateway;
        this.standIn = standIn;
        this.storeAndForward = storeAndForward;
        this.duplicates = duplicates;
        this.velocity = velocity;
        this.metrics = metrics;
        this.clock = clock;
    }

    public CompletableFuture<IsoMessage> authorize(IsoMessage request) {
        long start = System.nanoTime();
        CompletableFuture<IssuerOutcome> outcome;
        try {
            outcome = decide(request);
        } catch (RuntimeException e) {
            log.error("Failed to authorize {}", request, e);
            outcome = declineBySwitch(request, ResponseCode.SYSTEM_MALFUNCTION);
        }
        return outcome.thenApply(decision -> {
            IsoMessage response = Responses.replyTo(request)
                    .copyFrom(decision.response(), AUTH_ID_RESPONSE, RESPONSE_CODE)
                    .set(ADDITIONAL_RESPONSE_DATA, decision.source().code());
            metrics.authorization(response.get(RESPONSE_CODE), decision.source(), System.nanoTime() - start);
            if (log.isDebugEnabled()) {
                log.debug("{} -> {}", request, response);
            }
            return response;
        });
    }

    private CompletableFuture<IssuerOutcome> decide(IsoMessage request) {
        ResponseCode formatProblem = validate(request);
        if (formatProblem != null) {
            return declineBySwitch(request, formatProblem);
        }
        if (duplicates.isDuplicate(request)) {
            return declineBySwitch(request, ResponseCode.DUPLICATE_TRANSMISSION);
        }
        String pan = request.get(PAN);
        if (pan.length() < 12 || !Luhn.isValid(pan)) {
            return declineBySwitch(request, ResponseCode.INVALID_CARD_NUMBER);
        }
        if (isExpired(request.get(EXPIRATION_DATE))) {
            return declineBySwitch(request, ResponseCode.EXPIRED_CARD);
        }
        Issuer issuer = issuers.route(pan);
        if (issuer == null) {
            return declineBySwitch(request, ResponseCode.NO_SUCH_ISSUER);
        }
        switch (velocity.check(pan, Long.parseLong(request.get(AMOUNT)))) {
            case COUNT_EXCEEDED -> {
                return declineBySwitch(request, ResponseCode.EXCEEDS_FREQUENCY_LIMIT);
            }
            case AMOUNT_EXCEEDED -> {
                return declineBySwitch(request, ResponseCode.EXCEEDS_AMOUNT_LIMIT);
            }
            case OK -> {
            }
        }
        return gateway.send(issuer, request,
                reason -> standIn.decide(issuer, request, reason),
                (finalResponse, lateResponse) -> onLateIssuerResponse(issuer, request, finalResponse, lateResponse));
    }

    /**
     * The issuer answered after we had already stood in. If it approved (and placed a hold) but stand-in declined,
     * the cardholder's funds would stay held for a transaction the merchant was told was declined, so the switch
     * reverses the issuer's approval. If stand-in also approved, the 0120 advice reconciles it instead.
     */
    private void onLateIssuerResponse(Issuer issuer, IsoMessage request, IsoMessage finalResponse,
                                      IsoMessage lateResponse) {
        if (Responses.isApproved(lateResponse) && !Responses.isApproved(finalResponse)) {
            storeAndForward.enqueue(issuer, Responses.reversalOf(request, clock));
            metrics.lateApprovalReversed(issuer.id());
        }
    }

    static ResponseCode validate(IsoMessage request) {
        for (int field : MANDATORY_FIELDS) {
            if (!request.has(field)) {
                return ResponseCode.FORMAT_ERROR;
            }
        }
        String processingCode = request.get(PROCESSING_CODE);
        if (processingCode.length() != 6 || !SUPPORTED_TRANSACTION_TYPES.contains(processingCode.substring(0, 2))) {
            return ResponseCode.INVALID_TRANSACTION;
        }
        if (Long.parseLong(request.get(AMOUNT)) <= 0) {
            return ResponseCode.INVALID_AMOUNT;
        }
        String expiry = request.get(EXPIRATION_DATE);
        if (expiry.length() != 4) {
            return ResponseCode.FORMAT_ERROR;
        }
        int month = Integer.parseInt(expiry.substring(2));
        if (month < 1 || month > 12) {
            return ResponseCode.FORMAT_ERROR;
        }
        return null;
    }

    /** Field 14 is YYMM; a card is valid through the last day of its expiry month. */
    private boolean isExpired(String yymm) {
        YearMonth expiry = YearMonth.of(2000 + Integer.parseInt(yymm.substring(0, 2)), Integer.parseInt(yymm.substring(2)));
        return expiry.isBefore(YearMonth.now(clock));
    }

    private static CompletableFuture<IssuerOutcome> declineBySwitch(IsoMessage request, ResponseCode code) {
        return CompletableFuture.completedFuture(new IssuerOutcome(Responses.replyTo(request, code), ResponseSource.SWITCH));
    }
}
