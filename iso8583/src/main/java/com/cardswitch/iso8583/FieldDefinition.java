package com.cardswitch.iso8583;

import java.util.Objects;

/**
 * Wire format of one ISO 8583 data element.
 *
 * @param number     data element number (2..128; field 1 is the secondary bitmap and is handled by the codec)
 * @param name       human-readable name, used in message dumps
 * @param type       allowed character class
 * @param lengthType fixed or variable (LLVAR / LLLVAR) length
 * @param length     exact length for fixed fields, maximum length for variable fields
 */
public record FieldDefinition(int number, String name, FieldType type, LengthType lengthType, int length) {

    public FieldDefinition {
        if (number < 2 || number > IsoMessage.MAX_FIELD) {
            throw new IllegalArgumentException("Field number must be between 2 and 128: " + number);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lengthType, "lengthType");
        if (length < 1 || length > lengthType.maxLength()) {
            throw new IllegalArgumentException("Invalid length " + length + " for " + lengthType + " field " + number);
        }
    }

    public static FieldDefinition fixed(int number, String name, FieldType type, int length) {
        return new FieldDefinition(number, name, type, LengthType.FIXED, length);
    }

    public static FieldDefinition llvar(int number, String name, FieldType type, int maxLength) {
        return new FieldDefinition(number, name, type, LengthType.LLVAR, maxLength);
    }

    public static FieldDefinition lllvar(int number, String name, FieldType type, int maxLength) {
        return new FieldDefinition(number, name, type, LengthType.LLLVAR, maxLength);
    }
}
