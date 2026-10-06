package com.cardswitch.iso8583.card;

/**
 * Luhn (mod 10) check digit, used by every card number (ISO/IEC 7812).
 */
public final class Luhn {

    private Luhn() {
    }

    public static boolean isValid(String number) {
        if (number == null || number.length() < 2 || !isDigits(number)) {
            return false;
        }
        return sum(number, false) % 10 == 0;
    }

    /**
     * The digit that makes {@code partial + digit} pass the Luhn check.
     */
    public static char checkDigit(String partial) {
        if (partial == null || partial.isEmpty() || !isDigits(partial)) {
            throw new IllegalArgumentException("Expected digits");
        }
        int sum = sum(partial, true);
        return (char) ('0' + (10 - sum % 10) % 10);
    }

    /**
     * Sums digits from the right, doubling every second one. When {@code checkDigitPending} is true the rightmost
     * digit is the first one doubled, because the check digit will be appended after it.
     */
    private static int sum(String digits, boolean checkDigitPending) {
        int sum = 0;
        boolean doubleIt = checkDigitPending;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
