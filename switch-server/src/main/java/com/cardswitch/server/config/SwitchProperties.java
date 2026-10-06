package com.cardswitch.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Everything under {@code switch.*} in application.yml. Amounts are in minor units (cents).
 */
@ConfigurationProperties("switch")
public record SwitchProperties(
        Server server,
        Duration issuerTimeout,
        Duplicates duplicates,
        Velocity velocity,
        Breaker circuitBreaker,
        StoreAndForward storeAndForward,
        List<IssuerProperties> issuers) {

    /** ISO 8583 TCP listener. {@code ioThreads = 0} uses Netty's default (2 x cores). */
    public record Server(String host, int port, int ioThreads, int maxFrameLength) {
    }

    /** Retransmissions with the same acquirer, terminal, STAN and transmission time inside {@code window} are rejected. */
    public record Duplicates(Duration window, long maxEntries) {
    }

    /** Per-card attempt limits over a sliding window. */
    public record Velocity(Duration window, int maxCount, long maxAmount) {
    }

    /** Per-issuer circuit breaker (count-based sliding window). */
    public record Breaker(int slidingWindowSize, int minimumNumberOfCalls, float failureRateThreshold,
                          Duration waitDurationInOpenState, int permittedCallsInHalfOpenState) {
    }

    /** Delivery of stand-in advices and reversals to issuers once they are reachable again. */
    public record StoreAndForward(Duration interval, int batchSize, int maxAttempts, Duration deliveryTimeout,
                                  int maxDepth) {
    }

    public record IssuerProperties(String id, String name, List<String> bins, StandIn standIn, Simulator simulator) {
    }

    /**
     * Stand-in (STIP) parameters the issuer has agreed to: the switch approves on the issuer's behalf only up to
     * {@code maxAmount} per transaction and {@code maxCumulativeAmount} per card within {@code cumulativeWindow}.
     */
    public record StandIn(long maxAmount, long maxCumulativeAmount, Duration cumulativeWindow) {
    }

    /** The mock issuer host behind this issuer: its cardholder accounts and response latency. */
    public record Simulator(int accounts, long minBalance, long maxBalance, Duration medianLatency,
                            Duration p99Latency, long holdCapacity) {
    }
}
