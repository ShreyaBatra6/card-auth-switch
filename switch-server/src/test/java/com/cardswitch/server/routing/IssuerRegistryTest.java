package com.cardswitch.server.routing;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuerRegistryTest {

    private static Issuer issuer(String id, String... bins) {
        return new Issuer(id, id, List.of(bins), message -> new CompletableFuture<>(),
                CircuitBreaker.ofDefaults(id), null);
    }

    @Test
    void routesByLongestMatchingPrefix() {
        IssuerRegistry registry = new IssuerRegistry(List.of(
                issuer("broad", "400010"),
                issuer("carved-out", "40001099"),
                issuer("other", "51")));

        assertThat(registry.route("4000100000000006").id()).isEqualTo("broad");
        assertThat(registry.route("4000109900000001").id()).isEqualTo("carved-out");
        assertThat(registry.route("5100000000000001").id()).isEqualTo("other");
        assertThat(registry.route("4999990000000001")).isNull();
    }

    @Test
    void rejectsOverlappingBinAssignments() {
        assertThatThrownBy(() -> new IssuerRegistry(List.of(issuer("a", "400010"), issuer("b", "400010"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("400010");
    }
}
