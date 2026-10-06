package com.cardswitch.iso8583;

/**
 * Message type indicators (ISO 8583:1987) handled by the switch.
 */
public final class Mti {

    public static final String AUTHORIZATION_REQUEST = "0100";
    public static final String AUTHORIZATION_RESPONSE = "0110";
    public static final String AUTHORIZATION_ADVICE = "0120";
    public static final String AUTHORIZATION_ADVICE_RESPONSE = "0130";
    public static final String REVERSAL_REQUEST = "0400";
    public static final String REVERSAL_RESPONSE = "0410";
    public static final String NETWORK_MANAGEMENT_REQUEST = "0800";
    public static final String NETWORK_MANAGEMENT_RESPONSE = "0810";

    private Mti() {
    }

    /**
     * Whether the MTI expects a reply: the third digit (message function) is 0 (request) or 2 (advice).
     */
    public static boolean expectsResponse(String mti) {
        return isWellFormed(mti) && (mti.charAt(2) == '0' || mti.charAt(2) == '2');
    }

    /**
     * The MTI of the reply to {@code requestMti}, e.g. {@code 0100 -> 0110}, {@code 0120 -> 0130}.
     */
    public static String responseTo(String requestMti) {
        if (!expectsResponse(requestMti)) {
            throw new IllegalArgumentException("MTI " + requestMti + " is not a request or advice");
        }
        char[] digits = requestMti.toCharArray();
        digits[2]++;
        return new String(digits);
    }

    static boolean isWellFormed(String mti) {
        if (mti == null || mti.length() != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (mti.charAt(i) < '0' || mti.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
