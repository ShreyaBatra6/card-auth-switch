package com.cardswitch.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * Who made the decision in a response, carried in the first position of field 44.
 * <p>
 * This is a convention of this switch, loosely modeled on how card networks flag stand-in responses in field 44.
 */
public enum ResponseSource {

    /** Declined by the switch's own checks before the message was routed (format, Luhn, expiry, velocity...). */
    SWITCH("0", "Switch"),

    /** The issuer did not answer within the timeout; the switch decided using stand-in rules. */
    STAND_IN_TIMEOUT("1", "Stand-in (issuer timeout)"),

    /** The issuer's circuit breaker was open or the call failed; the switch decided using stand-in rules. */
    STAND_IN_UNAVAILABLE("4", "Stand-in (issuer unavailable)"),

    /** The issuer answered. */
    ISSUER("5", "Issuer");

    private static final Map<String, ResponseSource> BY_CODE = new HashMap<>();

    static {
        for (ResponseSource source : values()) {
            BY_CODE.put(source.code, source);
        }
    }

    private final String code;
    private final String description;

    ResponseSource(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public boolean isStandIn() {
        return this == STAND_IN_TIMEOUT || this == STAND_IN_UNAVAILABLE;
    }

    /**
     * Parses field 44 of a response; returns {@code null} if absent or unrecognized.
     */
    public static ResponseSource fromField44(String field44) {
        if (field44 == null || field44.isEmpty()) {
            return null;
        }
        return BY_CODE.get(field44.substring(0, 1));
    }
}
