package com.cardswitch.server.metrics;

import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.server.routing.Issuer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Micrometer instrumentation, exported at {@code /actuator/prometheus}.
 */
@Component
public class SwitchMetrics {

    private final MeterRegistry registry;
    private final Timer authorizationLatency;
    private final Counter malformedMessages;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> issuerLatency = new ConcurrentHashMap<>();

    public SwitchMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.authorizationLatency = Timer.builder("switch.authorization.latency")
                .description("Time from receiving an 0100 to having its 0110 ready, including the issuer round trip")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.99)
                .register(registry);
        this.malformedMessages = Counter.builder("switch.messages.malformed")
                .description("Frames that could not be parsed as ISO 8583 and were dropped")
                .register(registry);
        Gauge.builder("switch.connections.active", activeConnections, AtomicInteger::get).register(registry);
    }

    public void authorization(String responseCode, ResponseSource source, long nanos) {
        authorizationLatency.record(nanos, TimeUnit.NANOSECONDS);
        counter("switch.authorizations", "response_code", responseCode, "source", tagValue(source)).increment();
    }

    public void reversal(String responseCode, ResponseSource source) {
        counter("switch.reversals", "response_code", responseCode, "source", tagValue(source)).increment();
    }

    public void issuerResponse(String issuerId, long nanos) {
        issuerLatency.computeIfAbsent(issuerId, id -> Timer.builder("switch.issuer.latency")
                        .tag("issuer", id)
                        .publishPercentileHistogram()
                        .register(registry))
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void issuerTimeout(String issuerId) {
        counter("switch.issuer.timeouts", "issuer", issuerId).increment();
    }

    public void issuerError(String issuerId) {
        counter("switch.issuer.errors", "issuer", issuerId).increment();
    }

    public void lateIssuerResponse(String issuerId) {
        counter("switch.issuer.late_responses", "issuer", issuerId).increment();
    }

    public void lateApprovalReversed(String issuerId) {
        counter("switch.issuer.late_approvals_reversed", "issuer", issuerId).increment();
    }

    public void storeAndForwardDelivered(String issuerId, String mti) {
        counter("switch.saf.delivered", "issuer", issuerId, "mti", mti).increment();
    }

    public void storeAndForwardDropped(String issuerId, String reason) {
        counter("switch.saf.dropped", "issuer", issuerId, "reason", reason).increment();
    }

    public void registerStoreAndForwardDepth(String issuerId, Supplier<Number> depth) {
        Gauge.builder("switch.saf.depth", depth).tag("issuer", issuerId).register(registry);
    }

    /** 0 = closed, 1 = half open, 2 = open. */
    public void registerCircuitBreaker(Issuer issuer) {
        Gauge.builder("switch.issuer.circuit_state", issuer.circuitBreaker(), SwitchMetrics::stateValue)
                .tag("issuer", issuer.id())
                .register(registry);
    }

    public void malformedMessage() {
        malformedMessages.increment();
    }

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    /** Totals of {@code switch.authorizations} grouped by response code and by source, for the admin API. */
    public Map<String, Map<String, Long>> authorizationTotals() {
        Map<String, Long> byCode = new LinkedHashMap<>();
        Map<String, Long> bySource = new LinkedHashMap<>();
        registry.find("switch.authorizations").counters().forEach(c -> {
            long count = (long) c.count();
            byCode.merge(c.getId().getTag("response_code"), count, Long::sum);
            bySource.merge(c.getId().getTag("source"), count, Long::sum);
        });
        Map<String, Map<String, Long>> totals = new LinkedHashMap<>();
        totals.put("byResponseCode", byCode);
        totals.put("bySource", bySource);
        return totals;
    }

    private Counter counter(String name, String... tags) {
        String key = name + String.join("|", tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(name).tags(tags).register(registry));
    }

    private static String tagValue(ResponseSource source) {
        return source.name().toLowerCase();
    }

    private static double stateValue(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case CLOSED, DISABLED, METRICS_ONLY -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
        };
    }
}
