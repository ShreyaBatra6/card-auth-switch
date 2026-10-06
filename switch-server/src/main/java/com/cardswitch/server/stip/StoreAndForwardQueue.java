package com.cardswitch.server.stip;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.server.config.SwitchConfiguration;
import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.metrics.SwitchMetrics;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store-and-forward (SAF): messages the issuer must eventually receive but that cannot wait for it, i.e. 0120 advices
 * of stand-in approvals and 0400 reversals. Each issuer has a FIFO queue that is drained in the background whenever
 * its circuit breaker allows calls. Deliveries also go through the breaker, so a dead issuer stops being retried.
 * <p>
 * Delivery is at-least-once (issuers de-duplicate by RRN). The queue is in memory; a production switch would keep
 * it in a durable log so a restart does not lose advices.
 */
@Component
public class StoreAndForwardQueue {

    private static final Logger log = LoggerFactory.getLogger(StoreAndForwardQueue.class);

    private record Entry(IsoMessage message, int attempts) {
    }

    private final IssuerRegistry issuers;
    private final SwitchMetrics metrics;
    private final SwitchProperties.StoreAndForward config;
    private final Map<String, Deque<Entry>> queues = new HashMap<>();
    private final Map<String, AtomicInteger> depths = new HashMap<>();

    public StoreAndForwardQueue(IssuerRegistry issuers, SwitchProperties properties, SwitchMetrics metrics,
                                @Qualifier(SwitchConfiguration.SWITCH_SCHEDULER) ScheduledExecutorService scheduler) {
        this.issuers = issuers;
        this.metrics = metrics;
        this.config = properties.storeAndForward();
        for (Issuer issuer : issuers.all()) {
            AtomicInteger depth = new AtomicInteger();
            queues.put(issuer.id(), new ConcurrentLinkedDeque<>());
            depths.put(issuer.id(), depth);
            metrics.registerStoreAndForwardDepth(issuer.id(), depth::get);
            metrics.registerCircuitBreaker(issuer);
        }
        long interval = config.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::drainAll, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void enqueue(Issuer issuer, IsoMessage message) {
        AtomicInteger depth = depths.get(issuer.id());
        if (depth.incrementAndGet() > config.maxDepth()) {
            depth.decrementAndGet();
            metrics.storeAndForwardDropped(issuer.id(), "overflow");
            log.error("SAF queue for {} is full; dropping {}", issuer.id(), message);
            return;
        }
        queues.get(issuer.id()).addLast(new Entry(message, 0));
    }

    public int depth(String issuerId) {
        return depths.get(issuerId).get();
    }

    private void drainAll() {
        try {
            for (Issuer issuer : issuers.all()) {
                drain(issuer);
            }
        } catch (RuntimeException e) {
            log.error("SAF drain failed", e); // never let an exception cancel the periodic task
        }
    }

    private void drain(Issuer issuer) {
        Deque<Entry> queue = queues.get(issuer.id());
        CircuitBreaker breaker = issuer.circuitBreaker();
        for (int i = 0; i < config.batchSize() && !queue.isEmpty(); i++) {
            if (!breaker.tryAcquirePermission()) {
                return;
            }
            Entry entry = queue.pollFirst();
            if (entry == null) {
                breaker.releasePermission();
                return;
            }
            deliver(issuer, queue, entry);
        }
    }

    private void deliver(Issuer issuer, Deque<Entry> queue, Entry entry) {
        long start = System.nanoTime();
        CompletableFuture<IsoMessage> call;
        try {
            call = issuer.client().send(entry.message());
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.orTimeout(config.deliveryTimeout().toMillis(), TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
            long elapsed = System.nanoTime() - start;
            if (error == null) {
                issuer.circuitBreaker().onSuccess(elapsed, TimeUnit.NANOSECONDS);
                depths.get(issuer.id()).decrementAndGet();
                metrics.storeAndForwardDelivered(issuer.id(), entry.message().mti());
                return;
            }
            issuer.circuitBreaker().onError(elapsed, TimeUnit.NANOSECONDS, error);
            if (entry.attempts() + 1 >= config.maxAttempts()) {
                depths.get(issuer.id()).decrementAndGet();
                metrics.storeAndForwardDropped(issuer.id(), "max_attempts");
                log.error("Giving up on SAF delivery to {} after {} attempts: {}", issuer.id(), config.maxAttempts(), entry.message());
            } else {
                queue.addFirst(new Entry(entry.message(), entry.attempts() + 1));
            }
        });
    }
}
