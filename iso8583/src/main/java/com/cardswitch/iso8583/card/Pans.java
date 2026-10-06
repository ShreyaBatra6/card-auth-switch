package com.cardswitch.iso8583.card;

/**
 * Primary account number helpers.
 */
public final class Pans {

    private static final int TEST_PAN_LENGTH = 16;

    private Pans() {
    }

    /**
     * PCI DSS style masking: keep the first six (BIN) and last four digits, e.g. {@code 400010******0006}.
     */
    public static String mask(String pan) {
        if (pan == null) {
            return null;
        }
        if (pan.length() < 13) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    /**
     * Deterministic 16-digit test card: {@code bin + zero-padded index + Luhn check digit}.
     * The simulated issuers and the load generator both use this, so they agree on which cards exist.
     */
    public static String testCard(String bin, long index) {
        int indexDigits = TEST_PAN_LENGTH - 1 - bin.length();
        if (indexDigits < 1 || index < 0 || String.valueOf(index).length() > indexDigits) {
            throw new IllegalArgumentException("Index " + index + " does not fit after BIN " + bin);
        }
        String body = bin + String.format("%0" + indexDigits + "d", index);
        return body + Luhn.checkDigit(body);
    }
}
