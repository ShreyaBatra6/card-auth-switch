package com.cardswitch.server.auth;

import com.cardswitch.server.config.SwitchProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * Per-card velocity limits: at most {@code maxCount} authorization attempts and {@code maxAmount} total within a
 * sliding window. Counting attempts (not just approvals) is what catches card-testing attacks, where a stolen
 * number is tried many times in quick succession.
 * <p>
 * Each card's window is a small ring buffer of (timestamp, amount) guarded by its own lock, so different cards
 * never contend. Idle cards are evicted after one window.
 */
@Component
public class VelocityTracker {

    public enum Result { OK, COUNT_EXCEEDED, AMOUNT_EXCEEDED }

    private final long windowNanos;
    private final int maxCount;
    private final long maxAmount;
    private final LongSupplier nanoClock;
    private final Cache<String, SlidingWindow> windows;

    @Autowired
    public VelocityTracker(SwitchProperties properties) {
        this(properties.velocity(), System::nanoTime);
    }

    VelocityTracker(SwitchProperties.Velocity config, LongSupplier nanoClock) {
        this.windowNanos = config.window().toNanos();
        this.maxCount = config.maxCount();
        this.maxAmount = config.maxAmount();
        this.nanoClock = nanoClock;
        this.windows = Caffeine.newBuilder().expireAfterAccess(config.window()).build();
    }

    /**
     * Records an attempt for {@code pan} unless it would exceed a limit.
     */
    public Result check(String pan, long amount) {
        return windows.get(pan, key -> new SlidingWindow(maxCount))
                .tryRecord(nanoClock.getAsLong(), amount, windowNanos, maxAmount);
    }

    static final class SlidingWindow {

        private final long[] times;
        private final long[] amounts;
        private int head;
        private int size;
        private long total;

        SlidingWindow(int capacity) {
            this.times = new long[capacity];
            this.amounts = new long[capacity];
        }

        synchronized Result tryRecord(long now, long amount, long windowNanos, long maxAmount) {
            while (size > 0 && now - times[head] >= windowNanos) {
                total -= amounts[head];
                head = (head + 1) % times.length;
                size--;
            }
            if (size == times.length) {
                return Result.COUNT_EXCEEDED;
            }
            if (total + amount > maxAmount) {
                return Result.AMOUNT_EXCEEDED;
            }
            int tail = (head + size) % times.length;
            times[tail] = now;
            amounts[tail] = amount;
            size++;
            total += amount;
            return Result.OK;
        }
    }
}
