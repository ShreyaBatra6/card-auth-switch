package com.cardswitch.iso8583;

import com.cardswitch.iso8583.card.Pans;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * An ISO 8583 message: an MTI plus data elements 2..128, each held as its ASCII string value.
 * <p>
 * {@link #toString()} and {@link #dump(IsoSpec)} mask sensitive card data (PAN, expiry, track 2) so a message can be
 * logged without exposing it.
 */
public final class IsoMessage {

    public static final int MAX_FIELD = 128;

    private String mti;
    private final String[] fields = new String[MAX_FIELD + 1];

    public IsoMessage(String mti) {
        setMti(mti);
    }

    public String mti() {
        return mti;
    }

    public IsoMessage setMti(String mti) {
        if (!Mti.isWellFormed(mti)) {
            throw new IllegalArgumentException("MTI must be 4 digits");
        }
        this.mti = mti;
        return this;
    }

    public IsoMessage set(int field, String value) {
        checkFieldNumber(field);
        fields[field] = Objects.requireNonNull(value, "value");
        return this;
    }

    public String get(int field) {
        checkFieldNumber(field);
        return fields[field];
    }

    public boolean has(int field) {
        return get(field) != null;
    }

    public IsoMessage remove(int field) {
        checkFieldNumber(field);
        fields[field] = null;
        return this;
    }

    /**
     * Copies each listed field from {@code source} if it is present there.
     */
    public IsoMessage copyFrom(IsoMessage source, int... fieldNumbers) {
        for (int field : fieldNumbers) {
            String value = source.get(field);
            if (value != null) {
                fields[field] = value;
            }
        }
        return this;
    }

    public IsoMessage copy() {
        IsoMessage copy = new IsoMessage(mti);
        System.arraycopy(fields, 0, copy.fields, 0, fields.length);
        return copy;
    }

    /**
     * Multi-line, field-by-field description with names from {@code spec}; sensitive values are masked.
     */
    public String dump(IsoSpec spec) {
        StringBuilder sb = new StringBuilder("MTI ").append(mti).append('\n');
        for (int i = 2; i <= MAX_FIELD; i++) {
            if (fields[i] != null) {
                FieldDefinition def = spec.field(i);
                sb.append(String.format("  [%3d] %-36s %s%n", i, def != null ? def.name() : "?", "'" + masked(i) + "'"));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", mti + " {", "}");
        for (int i = 2; i <= MAX_FIELD; i++) {
            if (fields[i] != null) {
                joiner.add(i + "=" + masked(i));
            }
        }
        return joiner.toString();
    }

    private String masked(int field) {
        String value = fields[field];
        return switch (field) {
            case Field.PAN -> Pans.mask(value);
            case Field.EXPIRATION_DATE, Field.TRACK2 -> "*".repeat(value.length());
            default -> value;
        };
    }

    private static void checkFieldNumber(int field) {
        if (field < 2 || field > MAX_FIELD) {
            throw new IllegalArgumentException("Data element number must be between 2 and 128: " + field);
        }
    }
}
