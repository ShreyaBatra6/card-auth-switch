package com.cardswitch.server.admin;

import com.cardswitch.server.issuer.SimulatedIssuer;
import com.cardswitch.server.metrics.SwitchMetrics;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerRegistry;
import com.cardswitch.server.stip.StoreAndForwardQueue;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Operations API: issuer health, store-and-forward backlog, and fault injection into the simulated issuers so
 * stand-in processing can be demonstrated. Unauthenticated, and bound to localhost by default.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    public record SimulatorView(double medianLatencyMs, double p99LatencyMs, boolean available, int accounts) {
    }

    public record IssuerView(String id, String name, List<String> bins, String circuitBreaker, float failureRate,
                             int storeAndForwardDepth, SimulatorView simulator) {
    }

    /** Partial update; null fields are left unchanged. */
    public record SimulatorUpdate(Double medianLatencyMs, Double p99LatencyMs, Boolean available) {
    }

    private final IssuerRegistry issuers;
    private final StoreAndForwardQueue storeAndForward;
    private final SwitchMetrics metrics;

    public AdminController(IssuerRegistry issuers, StoreAndForwardQueue storeAndForward, SwitchMetrics metrics) {
        this.issuers = issuers;
        this.storeAndForward = storeAndForward;
        this.metrics = metrics;
    }

    @GetMapping("/issuers")
    public List<IssuerView> issuers() {
        return issuers.all().stream().map(this::view).toList();
    }

    @GetMapping("/issuers/{id}")
    public IssuerView issuer(@PathVariable String id) {
        return view(find(id));
    }

    @PutMapping("/issuers/{id}/simulator")
    public IssuerView updateSimulator(@PathVariable String id, @RequestBody SimulatorUpdate update) {
        Issuer issuer = find(id);
        SimulatedIssuer simulator = simulator(issuer);
        SimulatedIssuer.Behavior current = simulator.behavior();
        simulator.setBehavior(new SimulatedIssuer.Behavior(
                update.medianLatencyMs() != null ? millis(update.medianLatencyMs()) : current.medianLatency(),
                update.p99LatencyMs() != null ? millis(update.p99LatencyMs()) : current.p99Latency(),
                update.available() != null ? update.available() : current.available()));
        return view(issuer);
    }

    @GetMapping("/stats")
    public Map<String, Map<String, Long>> stats() {
        return metrics.authorizationTotals();
    }

    private IssuerView view(Issuer issuer) {
        SimulatedIssuer simulator = simulator(issuer);
        SimulatedIssuer.Behavior behavior = simulator.behavior();
        return new IssuerView(issuer.id(), issuer.name(), issuer.bins(),
                issuer.circuitBreaker().getState().name(),
                issuer.circuitBreaker().getMetrics().getFailureRate(),
                storeAndForward.depth(issuer.id()),
                new SimulatorView(toMillis(behavior.medianLatency()), toMillis(behavior.p99Latency()),
                        behavior.available(), simulator.accountCount()));
    }

    private Issuer find(String id) {
        return issuers.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No issuer " + id));
    }

    private static SimulatedIssuer simulator(Issuer issuer) {
        if (issuer.client() instanceof SimulatedIssuer simulator) {
            return simulator;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Issuer " + issuer.id() + " is not simulated");
    }

    private static Duration millis(double ms) {
        return Duration.ofNanos((long) (ms * 1_000_000));
    }

    private static double toMillis(Duration duration) {
        return duration.toNanos() / 1_000_000.0;
    }
}
