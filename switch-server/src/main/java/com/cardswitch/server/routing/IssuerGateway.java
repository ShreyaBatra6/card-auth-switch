package com.cardswitch.server.routing;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.server.config.SwitchConfiguration;
import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.metrics.SwitchMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Sends a message to an issuer with a deadline, guarded by that issuer's circuit breaker.
 * <ul>
 *   <li>Breaker open: skip the issuer and use the fallback immediately ({@link ResponseSource#STAND_IN_UNAVAILABLE}).</li>
 *   <li>No answer within {@code switch.issuer-timeout}: use the fallback ({@link ResponseSource#STAND_IN_TIMEOUT}).</li>
 *   <li>An answer that arrives after the fallback was used is handed to the caller's {@link LateResponseHandler}.</li>
 * </ul>
 * Nothing here blocks: the deadline is a scheduled task and whichever of "issuer answered" or "deadline fired"
 * happens first wins an atomic flag.
 */
@Component
public class IssuerGateway {

    @FunctionalInterface
    public interface LateResponseHandler {

        LateResponseHandler IGNORE = (finalResponse, lateIssuerResponse) -> {
        };

        /**
         * @param finalResponse      what the switch already decided (the fallback's response)
         * @param lateIssuerResponse what the issuer answered after the deadline
         */
        void handle(IsoMessage finalResponse, IsoMessage lateIssuerResponse);
    }

    private final ScheduledExecutorService timer;
    private final long timeoutNanos;
    private final SwitchMetrics metrics;

    public IssuerGateway(@Qualifier(SwitchConfiguration.SWITCH_SCHEDULER) ScheduledExecutorService timer,
                         SwitchProperties properties, SwitchMetrics metrics) {
        this.timer = timer;
        this.timeoutNanos = properties.issuerTimeout().toNanos();
        this.metrics = metrics;
    }

    /**
     * @param fallback builds the switch's own response when the issuer cannot answer; receives the reason
     */
    public CompletableFuture<IssuerOutcome> send(Issuer issuer, IsoMessage request,
                                                 Function<ResponseSource, IsoMessage> fallback,
                                                 LateResponseHandler onLateResponse) {
        CircuitBreaker breaker = issuer.circuitBreaker();
        CompletableFuture<IssuerOutcome> result = new CompletableFuture<>();
        if (!breaker.tryAcquirePermission()) {
            completeWithFallback(result, fallback, ResponseSource.STAND_IN_UNAVAILABLE);
            return result;
        }

        long start = System.nanoTime();
        AtomicBoolean decided = new AtomicBoolean();
        CompletableFuture<IsoMessage> call;
        try {
            call = issuer.client().send(request);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }

        ScheduledFuture<?> deadline = call.isDone() ? null : timer.schedule(() -> {
            if (decided.compareAndSet(false, true)) {
                breaker.onError(System.nanoTime() - start, NANOSECONDS, new TimeoutException("Issuer " + issuer.id() + " timed out"));
                metrics.issuerTimeout(issuer.id());
                completeWithFallback(result, fallback, ResponseSource.STAND_IN_TIMEOUT);
            }
        }, timeoutNanos, NANOSECONDS);

        call.whenComplete((response, error) -> {
            long elapsed = System.nanoTime() - start;
            if (decided.compareAndSet(false, true)) {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (error != null) {
                    breaker.onError(elapsed, NANOSECONDS, error);
                    metrics.issuerError(issuer.id());
                    completeWithFallback(result, fallback, ResponseSource.STAND_IN_UNAVAILABLE);
                } else {
                    breaker.onSuccess(elapsed, NANOSECONDS);
                    metrics.issuerResponse(issuer.id(), elapsed);
                    result.complete(new IssuerOutcome(response, ResponseSource.ISSUER));
                }
            } else if (error == null) {
                metrics.lateIssuerResponse(issuer.id());
                // Chained on the result so the fallback decision is guaranteed to be complete before we compare.
                result.thenAccept(outcome -> onLateResponse.handle(outcome.response(), response));
            }
        });
        return result;
    }

    private static void completeWithFallback(CompletableFuture<IssuerOutcome> result,
                                             Function<ResponseSource, IsoMessage> fallback, ResponseSource reason) {
        try {
            result.complete(new IssuerOutcome(fallback.apply(reason), reason));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
    }
}
