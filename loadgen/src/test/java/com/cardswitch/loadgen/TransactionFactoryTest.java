package com.cardswitch.loadgen;

import com.cardswitch.iso8583.Field;
import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.IsoSpec;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.card.Luhn;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFactoryTest {

    private final IsoCodec codec = new IsoCodec(IsoSpec.iso87Ascii());

    @Test
    void generatesWellFormedUniqueAuthorizations() {
        TransactionFactory factory = new TransactionFactory(List.of("400010", "400020"), 1_000,
                new TransactionFactory.Mix(0, 0, 0));
        Set<String> rrns = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            IsoMessage message = factory.authorization()
                    .set(Field.STAN, "000001")
                    .set(Field.TERMINAL_ID, "LOAD0001");
            IsoMessage decoded = codec.unpack(codec.pack(message));
            assertThat(Luhn.isValid(decoded.get(Field.PAN))).isTrue();
            assertThat(Long.parseLong(decoded.get(Field.AMOUNT))).isPositive();
            assertThat(rrns.add(decoded.get(Field.RRN))).as("RRN unique").isTrue();
        }
    }

    @Test
    void injectsInvalidCardsAtTheConfiguredRate() {
        TransactionFactory factory = new TransactionFactory(List.of("400010"), 1_000,
                new TransactionFactory.Mix(10, 0, 0));
        int invalid = 0;
        for (int i = 0; i < 10_000; i++) {
            if (!Luhn.isValid(factory.authorization().get(Field.PAN))) {
                invalid++;
            }
        }
        assertThat(invalid).isBetween(800, 1_200);
    }

    @Test
    void buildsReversalReferencingTheOriginal() {
        TransactionFactory factory = new TransactionFactory(List.of("400010"), 10, new TransactionFactory.Mix(0, 0, 0));
        IsoMessage original = factory.authorization().set(Field.STAN, "000042").set(Field.TERMINAL_ID, "LOAD0001");

        IsoMessage reversal = factory.reversalOf(original);

        assertThat(reversal.mti()).isEqualTo(Mti.REVERSAL_REQUEST);
        assertThat(reversal.get(Field.RRN)).isEqualTo(original.get(Field.RRN));
        assertThat(reversal.get(Field.ORIGINAL_DATA_ELEMENTS)).hasSize(42).startsWith("0100000042");
        assertThat(reversal.has(Field.EXPIRATION_DATE)).isFalse();
        codec.pack(reversal.set(Field.STAN, "000043"));
    }
}
