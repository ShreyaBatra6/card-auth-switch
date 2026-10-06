package com.cardswitch.server.stip;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.core.Responses;
import com.cardswitch.server.routing.Issuer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.AUTH_ID_RESPONSE;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;

/**
 * Stand-in processing (STIP): decides on the issuer's behalf when the issuer cannot answer.
 * <p>
 * The card has already passed the switch's own checks (Luhn, expiry, velocity). Stand-in then approves only within
 * the limits the issuer configured: a per-transaction ceiling and a per-card cumulative ceiling. Anything above is
 * declined with 91 (issuer unavailable). Every stand-in approval is queued as an 0120 advice so the issuer can post
 * it once it is reachable.
 */
@Component
public class StandInProcessor {

    private final StoreAndForwardQueue storeAndForward;
    /** Per issuer: card number -> amount approved in stand-in during the issuer's cumulative window. */
    private final Map<String, Cache<String, AtomicLong>> approvedInStandIn = new ConcurrentHashMap<>();
    private final AtomicLong authIds = new AtomicLong();

    public StandInProcessor(StoreAndForwardQueue storeAndForward) {
        this.storeAndForward = storeAndForward;
    }

    public IsoMessage decide(Issuer issuer, IsoMessage request, ResponseSource reason) {
        IsoMessage reply = Responses.replyTo(request);
        long amount = Long.parseLong(request.get(AMOUNT));
        SwitchProperties.StandIn limits = issuer.standIn();

        if (amount > limits.maxAmount() || !reserve(issuer, request.get(PAN), amount)) {
            return reply.set(RESPONSE_CODE, ResponseCode.ISSUER_UNAVAILABLE.code());
        }
        reply.set(AUTH_ID_RESPONSE, String.format("S%05d", authIds.incrementAndGet() % 100_000))
                .set(RESPONSE_CODE, ResponseCode.APPROVED.code());

        IsoMessage advice = request.copy()
                .setMti(Mti.AUTHORIZATION_ADVICE)
                .set(AUTH_ID_RESPONSE, reply.get(AUTH_ID_RESPONSE))
                .set(RESPONSE_CODE, ResponseCode.APPROVED.code())
                .set(ADDITIONAL_RESPONSE_DATA, reason.code());
        storeAndForward.enqueue(issuer, advice);
        return reply;
    }

    private boolean reserve(Issuer issuer, String pan, long amount) {
        long max = issuer.standIn().maxCumulativeAmount();
        AtomicLong total = approvedInStandIn
                .computeIfAbsent(issuer.id(), id -> Caffeine.newBuilder()
                        .expireAfterWrite(issuer.standIn().cumulativeWindow())
                        .build())
                .get(pan, key -> new AtomicLong());
        long current;
        do {
            current = total.get();
            if (current + amount > max) {
                return false;
            }
        } while (!total.compareAndSet(current, current + amount));
        return true;
    }
}
