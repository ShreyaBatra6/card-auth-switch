package com.cardswitch.server.auth;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.server.core.Responses;
import com.cardswitch.server.metrics.SwitchMetrics;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerGateway;
import com.cardswitch.server.routing.IssuerOutcome;
import com.cardswitch.server.routing.IssuerRegistry;
import com.cardswitch.server.stip.StoreAndForwardQueue;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;
import static com.cardswitch.iso8583.Field.RRN;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;

/**
 * Handles 0400 reversals from acquirers, e.g. when a terminal timed out waiting for an 0110 or the sale was voided.
 * The original authorization is identified by RRN (field 37).
 * <p>
 * A reversal must reach the issuer eventually, but the acquirer should not wait on an unavailable issuer: in that
 * case the switch acknowledges it and delivers it later through store-and-forward.
 */
@Service
public class ReversalService {

    private static final int[] MANDATORY_FIELDS = {PAN, AMOUNT, TRANSMISSION_DATE_TIME, STAN, RRN, TERMINAL_ID};

    private final IssuerRegistry issuers;
    private final IssuerGateway gateway;
    private final StoreAndForwardQueue storeAndForward;
    private final SwitchMetrics metrics;

    public ReversalService(IssuerRegistry issuers, IssuerGateway gateway, StoreAndForwardQueue storeAndForward,
                           SwitchMetrics metrics) {
        this.issuers = issuers;
        this.gateway = gateway;
        this.storeAndForward = storeAndForward;
        this.metrics = metrics;
    }

    public CompletableFuture<IsoMessage> reverse(IsoMessage request) {
        return decide(request).thenApply(outcome -> {
            IsoMessage response = Responses.replyTo(request)
                    .copyFrom(outcome.response(), RESPONSE_CODE)
                    .set(ADDITIONAL_RESPONSE_DATA, outcome.source().code());
            metrics.reversal(response.get(RESPONSE_CODE), outcome.source());
            return response;
        });
    }

    private CompletableFuture<IssuerOutcome> decide(IsoMessage request) {
        for (int field : MANDATORY_FIELDS) {
            if (!request.has(field)) {
                return switchResponse(request, ResponseCode.FORMAT_ERROR);
            }
        }
        Issuer issuer = issuers.route(request.get(PAN));
        if (issuer == null) {
            return switchResponse(request, ResponseCode.NO_SUCH_ISSUER);
        }
        return gateway.send(issuer, request, reason -> {
            storeAndForward.enqueue(issuer, request.copy());
            return Responses.replyTo(request, ResponseCode.APPROVED);
        }, IssuerGateway.LateResponseHandler.IGNORE);
    }

    private static CompletableFuture<IssuerOutcome> switchResponse(IsoMessage request, ResponseCode code) {
        return CompletableFuture.completedFuture(new IssuerOutcome(Responses.replyTo(request, code), ResponseSource.SWITCH));
    }
}
