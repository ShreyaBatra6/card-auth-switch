package com.cardswitch.iso8583;

/**
 * Character classes used by ISO 8583 data elements. Values are carried as ASCII text.
 */
public enum FieldType {

    /** {@code n}: digits only. Fixed-length values are left-padded with zeros. */
    NUMERIC,

    /** {@code an}: letters and digits. Space is accepted because fixed-length values are right-padded with it. */
    ALPHANUMERIC,

    /** {@code ans}: any printable ASCII character. */
    ALPHANUMERIC_SPECIAL,

    /** {@code z}: track-2 code set: digits plus the {@code =} / {@code D} field separators. */
    TRACK2;

    boolean accepts(char c) {
        return switch (this) {
            case NUMERIC -> c >= '0' && c <= '9';
            case ALPHANUMERIC -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ';
            case ALPHANUMERIC_SPECIAL -> c >= 0x20 && c <= 0x7E;
            case TRACK2 -> (c >= '0' && c <= '9') || c == '=' || c == 'D';
        };
    }

    char padChar() {
        return this == NUMERIC ? '0' : ' ';
    }

    boolean padsLeft() {
        return this == NUMERIC;
    }
}
