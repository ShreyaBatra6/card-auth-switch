package com.cardswitch.iso8583;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoMessageTest {

    @Test
    void masksCardDataWhenPrinted() {
        IsoMessage message = new IsoMessage("0100")
                .set(Field.PAN, "4000100000000006")
                .set(Field.EXPIRATION_DATE, "2812")
                .set(Field.STAN, "000001");

        assertThat(message.toString())
                .contains("2=400010******0006")
                .contains("14=****")
                .contains("11=000001")
                .doesNotContain("4000100000000006")
                .doesNotContain("2812");
        assertThat(message.dump(IsoSpec.iso87Ascii()))
                .contains("Primary account number")
                .doesNotContain("4000100000000006");
    }

    @Test
    void derivesResponseMti() {
        assertThat(Mti.responseTo("0100")).isEqualTo("0110");
        assertThat(Mti.responseTo("0120")).isEqualTo("0130");
        assertThat(Mti.responseTo("0400")).isEqualTo("0410");
        assertThat(Mti.responseTo("0800")).isEqualTo("0810");
        assertThat(Mti.expectsResponse("0110")).isFalse();
        assertThatThrownBy(() -> Mti.responseTo("0110")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyIsIndependent() {
        IsoMessage original = new IsoMessage("0100").set(Field.STAN, "000001");
        IsoMessage copy = original.copy().set(Field.STAN, "000002");
        assertThat(original.get(Field.STAN)).isEqualTo("000001");
        assertThat(copy.get(Field.STAN)).isEqualTo("000002");
    }

    @Test
    void rejectsBitmapFieldNumber() {
        assertThatThrownBy(() -> new IsoMessage("0100").set(1, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
