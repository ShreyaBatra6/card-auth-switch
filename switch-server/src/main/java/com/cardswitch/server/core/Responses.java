package com.cardswitch.server.core;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.ResponseCode;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.cardswitch.iso8583.Field.ACQUIRER_ID;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.CURRENCY_CODE;
import static com.cardswitch.iso8583.Field.LOCAL_DATE;
import static com.cardswitch.iso8583.Field.LOCAL_TIME;
import static com.cardswitch.iso8583.Field.MERCHANT_ID;
import static com.cardswitch.iso8583.Field.NETWORK_MANAGEMENT_CODE;
import static com.cardswitch.iso8583.Field.ORIGINAL_DATA_ELEMENTS;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.PROCESSING_CODE;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;
import static com.cardswitch.iso8583.Field.RRN;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;

/**
 * Builders for response and reversal messages.
 */
public final class Responses {

    /**
     * Fields echoed from a request into its response. Expiry date and track 2 are deliberately not echoed.
     */
    private static final int[] ECHOED_FIELDS = {
            PAN, PROCESSING_CODE, AMOUNT, TRANSMISSION_DATE_TIME, STAN, LOCAL_TIME, LOCAL_DATE, ACQUIRER_ID,
            RRN, TERMINAL_ID, MERCHANT_ID, CURRENCY_CODE, NETWORK_MANAGEMENT_CODE, ORIGINAL_DATA_ELEMENTS
    };

    private static final int[] REVERSAL_FIELDS = {
            PAN, PROCESSING_CODE, AMOUNT, STAN, LOCAL_TIME, LOCAL_DATE, ACQUIRER_ID, RRN, TERMINAL_ID,
            MERCHANT_ID, CURRENCY_CODE
    };

    private static final DateTimeFormatter TRANSMISSION_TIME =
            DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);

    private Responses() {
    }

    public static IsoMessage replyTo(IsoMessage request) {
        return new IsoMessage(Mti.responseTo(request.mti())).copyFrom(request, ECHOED_FIELDS);
    }

    public static IsoMessage replyTo(IsoMessage request, ResponseCode code) {
        return replyTo(request).set(RESPONSE_CODE, code.code());
    }

    public static boolean isApproved(IsoMessage response) {
        return ResponseCode.APPROVED.code().equals(response.get(RESPONSE_CODE));
    }

    /**
     * A 0400 reversing {@code original} (a 0100). Field 90 identifies the original: its MTI, STAN, transmission time
     * and acquirer.
     */
    public static IsoMessage reversalOf(IsoMessage original, Clock clock) {
        String acquirer = original.has(ACQUIRER_ID) ? original.get(ACQUIRER_ID) : "";
        String originalData = original.mti()
                + original.get(STAN)
                + original.get(TRANSMISSION_DATE_TIME)
                + "0".repeat(11 - acquirer.length()) + acquirer
                + "0".repeat(11);
        return new IsoMessage(Mti.REVERSAL_REQUEST)
                .copyFrom(original, REVERSAL_FIELDS)
                .set(TRANSMISSION_DATE_TIME, TRANSMISSION_TIME.format(clock.instant()))
                .set(ORIGINAL_DATA_ELEMENTS, originalData);
    }
}
