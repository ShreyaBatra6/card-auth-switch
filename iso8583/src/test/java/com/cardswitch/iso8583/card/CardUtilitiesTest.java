package com.cardswitch.iso8583.card;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CardUtilitiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444", "378282246310005", "79927398713"})
    void acceptsValidNumbers(String number) {
        assertThat(Luhn.isValid(number)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "79927398710", "", "4111-1111-1111-1111", "0"})
    void rejectsInvalidNumbers(String number) {
        assertThat(Luhn.isValid(number)).isFalse();
    }

    @Test
    void computesCheckDigit() {
        assertThat(Luhn.checkDigit("7992739871")).isEqualTo('3');
        assertThat(Luhn.checkDigit("411111111111111")).isEqualTo('1');
    }

    @Test
    void generatesDeterministicValidTestCards() {
        String pan = Pans.testCard("400010", 42);
        assertThat(pan).hasSize(16).startsWith("400010000000042");
        assertThat(Luhn.isValid(pan)).isTrue();
        assertThat(Pans.testCard("400010", 42)).isEqualTo(pan);
    }

    @Test
    void masksPan() {
        assertThat(Pans.mask("4000100000000006")).isEqualTo("400010******0006");
        assertThat(Pans.mask("1234")).isEqualTo("****");
    }
}
