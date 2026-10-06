package com.cardswitch.iso8583;

import java.util.HashMap;
import java.util.Map;

/**
 * Field 39 response codes (ISO 8583:1987) used by the switch and the simulated issuers.
 */
public enum ResponseCode {

    APPROVED("00", "Approved"),
    DO_NOT_HONOR("05", "Do not honor"),
    INVALID_TRANSACTION("12", "Invalid transaction"),
    INVALID_AMOUNT("13", "Invalid amount"),
    INVALID_CARD_NUMBER("14", "Invalid card number"),
    NO_SUCH_ISSUER("15", "No such issuer"),
    UNABLE_TO_LOCATE_ORIGINAL("25", "Unable to locate original transaction"),
    FORMAT_ERROR("30", "Format error"),
    STOLEN_CARD("43", "Stolen card, pick up"),
    INSUFFICIENT_FUNDS("51", "Not sufficient funds"),
    EXPIRED_CARD("54", "Expired card"),
    EXCEEDS_AMOUNT_LIMIT("61", "Exceeds amount limit"),
    EXCEEDS_FREQUENCY_LIMIT("65", "Exceeds frequency limit"),
    ISSUER_UNAVAILABLE("91", "Issuer or switch inoperative"),
    DUPLICATE_TRANSMISSION("94", "Duplicate transmission"),
    SYSTEM_MALFUNCTION("96", "System malfunction");

    private static final Map<String, ResponseCode> BY_CODE = new HashMap<>();

    static {
        for (ResponseCode rc : values()) {
            BY_CODE.put(rc.code, rc);
        }
    }

    private final String code;
    private final String description;

    ResponseCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /**
     * Human-readable description of a raw field 39 value, including codes this enum does not know.
     */
    public static String describe(String code) {
        ResponseCode rc = BY_CODE.get(code);
        return rc != null ? rc.description : "Unknown";
    }
}
