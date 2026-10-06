package com.cardswitch.iso8583;

import static com.cardswitch.iso8583.FieldDefinition.fixed;
import static com.cardswitch.iso8583.FieldDefinition.llvar;
import static com.cardswitch.iso8583.FieldDefinition.lllvar;
import static com.cardswitch.iso8583.FieldType.ALPHANUMERIC;
import static com.cardswitch.iso8583.FieldType.ALPHANUMERIC_SPECIAL;
import static com.cardswitch.iso8583.FieldType.NUMERIC;
import static com.cardswitch.iso8583.FieldType.TRACK2;

/**
 * A data dictionary: the wire format of every field a message may carry.
 * A field that is not in the spec is rejected by the codec in both directions.
 */
public final class IsoSpec {

    private static final IsoSpec ISO87_ASCII = builder()
            .add(llvar(Field.PAN, "Primary account number", NUMERIC, 19))
            .add(fixed(Field.PROCESSING_CODE, "Processing code", NUMERIC, 6))
            .add(fixed(Field.AMOUNT, "Amount, transaction", NUMERIC, 12))
            .add(fixed(Field.TRANSMISSION_DATE_TIME, "Transmission date & time", NUMERIC, 10))
            .add(fixed(Field.STAN, "System trace audit number", NUMERIC, 6))
            .add(fixed(Field.LOCAL_TIME, "Time, local transaction", NUMERIC, 6))
            .add(fixed(Field.LOCAL_DATE, "Date, local transaction", NUMERIC, 4))
            .add(fixed(Field.EXPIRATION_DATE, "Date, expiration", NUMERIC, 4))
            .add(fixed(Field.MERCHANT_TYPE, "Merchant type", NUMERIC, 4))
            .add(fixed(Field.ACQUIRER_COUNTRY, "Acquiring institution country code", NUMERIC, 3))
            .add(fixed(Field.POS_ENTRY_MODE, "POS entry mode", NUMERIC, 3))
            .add(fixed(Field.POS_CONDITION_CODE, "POS condition code", NUMERIC, 2))
            .add(llvar(Field.ACQUIRER_ID, "Acquiring institution ID code", NUMERIC, 11))
            .add(llvar(Field.TRACK2, "Track 2 data", TRACK2, 37))
            .add(fixed(Field.RRN, "Retrieval reference number", ALPHANUMERIC, 12))
            .add(fixed(Field.AUTH_ID_RESPONSE, "Authorization ID response", ALPHANUMERIC, 6))
            .add(fixed(Field.RESPONSE_CODE, "Response code", ALPHANUMERIC, 2))
            .add(fixed(Field.TERMINAL_ID, "Card acceptor terminal ID", ALPHANUMERIC_SPECIAL, 8))
            .add(fixed(Field.MERCHANT_ID, "Card acceptor ID code", ALPHANUMERIC_SPECIAL, 15))
            .add(fixed(Field.MERCHANT_NAME_LOCATION, "Card acceptor name/location", ALPHANUMERIC_SPECIAL, 40))
            .add(llvar(Field.ADDITIONAL_RESPONSE_DATA, "Additional response data", ALPHANUMERIC_SPECIAL, 25))
            .add(lllvar(Field.ADDITIONAL_DATA_PRIVATE, "Additional data, private", ALPHANUMERIC_SPECIAL, 999))
            .add(fixed(Field.CURRENCY_CODE, "Currency code, transaction", NUMERIC, 3))
            .add(lllvar(Field.ADDITIONAL_AMOUNTS, "Additional amounts", ALPHANUMERIC, 120))
            .add(fixed(Field.NETWORK_MANAGEMENT_CODE, "Network management information code", NUMERIC, 3))
            .add(fixed(Field.ORIGINAL_DATA_ELEMENTS, "Original data elements", NUMERIC, 42))
            .build();

    private final FieldDefinition[] fields;

    private IsoSpec(FieldDefinition[] fields) {
        this.fields = fields;
    }

    /**
     * The ISO 8583:1987 subset used by the switch, with ASCII-encoded data elements and binary bitmaps.
     */
    public static IsoSpec iso87Ascii() {
        return ISO87_ASCII;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the definition of {@code number}, or {@code null} if the field is not part of this spec
     */
    public FieldDefinition field(int number) {
        return number >= 0 && number < fields.length ? fields[number] : null;
    }

    public static final class Builder {

        private final FieldDefinition[] fields = new FieldDefinition[IsoMessage.MAX_FIELD + 1];

        private Builder() {
        }

        public Builder add(FieldDefinition definition) {
            if (fields[definition.number()] != null) {
                throw new IllegalArgumentException("Field " + definition.number() + " defined twice");
            }
            fields[definition.number()] = definition;
            return this;
        }

        public IsoSpec build() {
            return new IsoSpec(fields.clone());
        }
    }
}
