package com.cardswitch.iso8583;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoCodecTest {

    private final IsoCodec codec = new IsoCodec(IsoSpec.iso87Ascii());

    @Test
    void roundTripsAuthorizationRequest() {
        IsoMessage request = new IsoMessage(Mti.AUTHORIZATION_REQUEST)
                .set(Field.PAN, "4000100000000006")
                .set(Field.PROCESSING_CODE, "000000")
                .set(Field.AMOUNT, "000000002500")
                .set(Field.TRANSMISSION_DATE_TIME, "1006153000")
                .set(Field.STAN, "000123")
                .set(Field.EXPIRATION_DATE, "2812")
                .set(Field.ACQUIRER_ID, "123456")
                .set(Field.RRN, "628012000123")
                .set(Field.TERMINAL_ID, "TERM0001")
                .set(Field.MERCHANT_ID, "MERCHANT0000001")
                .set(Field.CURRENCY_CODE, "840");

        IsoMessage decoded = codec.unpack(codec.pack(request));

        assertThat(decoded.mti()).isEqualTo("0100");
        for (int field : new int[]{2, 3, 4, 7, 11, 14, 32, 37, 41, 42, 49}) {
            assertThat(decoded.get(field)).as("field %d", field).isEqualTo(request.get(field));
        }
        assertThat(decoded.has(Field.RESPONSE_CODE)).isFalse();
    }

    @Test
    void encodesExactWireFormatWithSecondaryBitmap() {
        IsoMessage echo = new IsoMessage(Mti.NETWORK_MANAGEMENT_REQUEST)
                .set(Field.TRANSMISSION_DATE_TIME, "1006153000")
                .set(Field.STAN, "000001")
                .set(Field.NETWORK_MANAGEMENT_CODE, "301");

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes("0800".getBytes(StandardCharsets.US_ASCII));
        // primary bitmap: bit 1 (secondary present), bit 7, bit 11
        expected.writeBytes(new byte[]{(byte) 0x82, 0x20, 0, 0, 0, 0, 0, 0});
        // secondary bitmap: bit 70
        expected.writeBytes(new byte[]{0x04, 0, 0, 0, 0, 0, 0, 0});
        expected.writeBytes("1006153000000001301".getBytes(StandardCharsets.US_ASCII));

        assertThat(codec.pack(echo)).isEqualTo(expected.toByteArray());
        assertThat(codec.unpack(expected.toByteArray()).get(Field.NETWORK_MANAGEMENT_CODE)).isEqualTo("301");
    }

    @Test
    void omitsSecondaryBitmapWhenOnlyPrimaryFieldsArePresent() {
        byte[] packed = codec.pack(new IsoMessage("0800").set(Field.STAN, "000001"));
        assertThat(packed).hasSize(4 + 8 + 6);
        assertThat(packed[4] & 0x80).isZero();
    }

    @Test
    void padsFixedLengthFields() {
        IsoMessage message = new IsoMessage("0110")
                .set(Field.AMOUNT, "2500")
                .set(Field.TERMINAL_ID, "T1");

        IsoMessage decoded = codec.unpack(codec.pack(message));

        assertThat(decoded.get(Field.AMOUNT)).isEqualTo("000000002500");
        assertThat(decoded.get(Field.TERMINAL_ID)).isEqualTo("T1      ");
    }

    @Test
    void writesLlvarLengthPrefix() {
        byte[] packed = codec.pack(new IsoMessage("0100").set(Field.PAN, "4111111111111111"));
        String body = new String(packed, 12, packed.length - 12, StandardCharsets.US_ASCII);
        assertThat(body).isEqualTo("164111111111111111");
    }

    @Test
    void rejectsValueLongerThanFieldLength() {
        IsoMessage message = new IsoMessage("0100").set(Field.STAN, "1234567");
        assertThatThrownBy(() -> codec.pack(message))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("Field 11");
    }

    @Test
    void rejectsNonDigitsInNumericField() {
        IsoMessage message = new IsoMessage("0100").set(Field.AMOUNT, "12A");
        assertThatThrownBy(() -> codec.pack(message))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("NUMERIC");
    }

    @Test
    void rejectsTruncatedMessage() {
        byte[] packed = codec.pack(new IsoMessage("0100").set(Field.PAN, "4111111111111111"));
        byte[] truncated = Arrays.copyOf(packed, packed.length - 3);
        assertThatThrownBy(() -> codec.unpack(truncated))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] packed = codec.pack(new IsoMessage("0800").set(Field.STAN, "000001"));
        byte[] padded = Arrays.copyOf(packed, packed.length + 2);
        assertThatThrownBy(() -> codec.unpack(padded))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    void rejectsFieldThatIsNotInTheSpec() {
        byte[] message = new byte[12];
        System.arraycopy("0100".getBytes(StandardCharsets.US_ASCII), 0, message, 0, 4);
        message[4] = 0x40; // bit 2 present...
        IsoSpec emptySpec = IsoSpec.builder().build(); // ...but not defined
        assertThatThrownBy(() -> new IsoCodec(emptySpec).unpack(message))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("not defined");
    }

    @Test
    void rejectsVariableLengthAboveMaximum() {
        byte[] message = new byte[12 + 2];
        System.arraycopy("0100".getBytes(StandardCharsets.US_ASCII), 0, message, 0, 4);
        message[4] = 0x40;
        message[12] = '2';
        message[13] = '0'; // PAN of 20 digits, max is 19
        assertThatThrownBy(() -> codec.unpack(message))
                .isInstanceOf(IsoFormatException.class)
                .hasMessageContaining("exceeds maximum 19");
    }

    @Test
    void rejectsMalformedMti() {
        byte[] message = "01A0".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> codec.unpack(message)).isInstanceOf(IsoFormatException.class);
    }

    @Test
    void exceptionMessagesNeverContainFieldValues() {
        IsoMessage message = new IsoMessage("0100").set(Field.PAN, "4000100000000006X");
        assertThatThrownBy(() -> codec.pack(message))
                .isInstanceOf(IsoFormatException.class)
                .message().doesNotContain("4000100000000006");
    }
}
