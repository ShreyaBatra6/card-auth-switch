package com.cardswitch.iso8583;

/**
 * How the length of a data element is determined on the wire.
 */
public enum LengthType {

    /** Always exactly {@code length} characters. */
    FIXED(0),

    /** Two ASCII digits of length prefix, then up to 99 characters. */
    LLVAR(2),

    /** Three ASCII digits of length prefix, then up to 999 characters. */
    LLLVAR(3);

    private final int prefixDigits;

    LengthType(int prefixDigits) {
        this.prefixDigits = prefixDigits;
    }

    public int prefixDigits() {
        return prefixDigits;
    }

    int maxLength() {
        return switch (this) {
            case FIXED -> Integer.MAX_VALUE;
            case LLVAR -> 99;
            case LLLVAR -> 999;
        };
    }
}
