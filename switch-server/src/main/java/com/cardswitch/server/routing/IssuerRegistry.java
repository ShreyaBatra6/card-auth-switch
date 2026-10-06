package com.cardswitch.server.routing;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The issuers known to the switch and the BIN routing table that maps a card number to its issuer.
 * <p>
 * Routing is longest-prefix match, so a more specific range (e.g. {@code 40001099}) can be carved out of a broader
 * one ({@code 400010}) and sent to a different issuer.
 */
public final class IssuerRegistry {

    private final Map<String, Issuer> byId = new LinkedHashMap<>();
    private final Map<String, Issuer> byPrefix = new HashMap<>();
    private final int shortestPrefix;
    private final int longestPrefix;

    public IssuerRegistry(List<Issuer> issuers) {
        int shortest = Integer.MAX_VALUE;
        int longest = 0;
        for (Issuer issuer : issuers) {
            if (byId.putIfAbsent(issuer.id(), issuer) != null) {
                throw new IllegalArgumentException("Duplicate issuer id " + issuer.id());
            }
            for (String bin : issuer.bins()) {
                Issuer previous = byPrefix.putIfAbsent(bin, issuer);
                if (previous != null) {
                    throw new IllegalArgumentException("BIN " + bin + " assigned to both " + previous.id() + " and " + issuer.id());
                }
                shortest = Math.min(shortest, bin.length());
                longest = Math.max(longest, bin.length());
            }
        }
        this.shortestPrefix = shortest;
        this.longestPrefix = longest;
    }

    /**
     * @return the issuer owning the longest configured prefix of {@code pan}, or {@code null} if none matches
     */
    public Issuer route(String pan) {
        for (int length = Math.min(longestPrefix, pan.length()); length >= shortestPrefix; length--) {
            Issuer issuer = byPrefix.get(pan.substring(0, length));
            if (issuer != null) {
                return issuer;
            }
        }
        return null;
    }

    public Optional<Issuer> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Issuer> all() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
