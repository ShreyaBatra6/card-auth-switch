package com.cardswitch.server.auth;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.server.config.SwitchProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import static com.cardswitch.iso8583.Field.ACQUIRER_ID;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;

/**
 * Rejects retransmissions: the same acquirer, terminal, STAN and transmission time seen again within the window.
 * Without this, an acquirer that retries after a lost response would charge the cardholder twice.
 */
@Component
public class DuplicateDetector {

    private final Cache<String, Boolean> seen;

    public DuplicateDetector(SwitchProperties properties) {
        this.seen = Caffeine.newBuilder()
                .expireAfterWrite(properties.duplicates().window())
                .maximumSize(properties.duplicates().maxEntries())
                .build();
    }

    /**
     * Records the message and reports whether it had already been seen.
     */
    public boolean isDuplicate(IsoMessage request) {
        String key = request.get(ACQUIRER_ID) + '|' + request.get(TERMINAL_ID) + '|' + request.get(STAN)
                + '|' + request.get(TRANSMISSION_DATE_TIME);
        return seen.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
