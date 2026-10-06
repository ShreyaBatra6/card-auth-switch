package com.cardswitch.iso8583;

/**
 * Data element numbers used by this switch (ISO 8583:1987 numbering).
 */
public final class Field {

    public static final int PAN = 2;
    public static final int PROCESSING_CODE = 3;
    public static final int AMOUNT = 4;
    public static final int TRANSMISSION_DATE_TIME = 7;
    public static final int STAN = 11;
    public static final int LOCAL_TIME = 12;
    public static final int LOCAL_DATE = 13;
    public static final int EXPIRATION_DATE = 14;
    public static final int MERCHANT_TYPE = 18;
    public static final int ACQUIRER_COUNTRY = 19;
    public static final int POS_ENTRY_MODE = 22;
    public static final int POS_CONDITION_CODE = 25;
    public static final int ACQUIRER_ID = 32;
    public static final int TRACK2 = 35;
    public static final int RRN = 37;
    public static final int AUTH_ID_RESPONSE = 38;
    public static final int RESPONSE_CODE = 39;
    public static final int TERMINAL_ID = 41;
    public static final int MERCHANT_ID = 42;
    public static final int MERCHANT_NAME_LOCATION = 43;
    public static final int ADDITIONAL_RESPONSE_DATA = 44;
    public static final int ADDITIONAL_DATA_PRIVATE = 48;
    public static final int CURRENCY_CODE = 49;
    public static final int ADDITIONAL_AMOUNTS = 54;
    public static final int NETWORK_MANAGEMENT_CODE = 70;
    public static final int ORIGINAL_DATA_ELEMENTS = 90;

    private Field() {
    }
}
