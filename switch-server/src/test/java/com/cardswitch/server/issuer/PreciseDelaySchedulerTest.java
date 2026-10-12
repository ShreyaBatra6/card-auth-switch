package com.cardswitch.server.issuer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PreciseDelaySchedulerTest {

    private final PreciseDelayScheduler scheduler = new PreciseDelayScheduler("test-timer");

    @AfterEach
    void close() {
        scheduler.close();
    }

    @Test
    void firesShortDelaysOnTime() throws InterruptedException {
        long[] lateness = new long[50];
        for (int i = 0; i < lateness.length; i++) {
            CountDownLatch fired = new CountDownLatch(1);
            long start = System.nanoTime();
            long[] firedAt = new long[1];
            scheduler.schedule(() -> {
                firedAt[0] = System.nanoTime();
                fired.countDown();
            }, 2, TimeUnit.MILLISECONDS);
            assertThat(fired.await(1, TimeUnit.SECONDS)).isTrue();
            lateness[i] = firedAt[0] - start - TimeUnit.MILLISECONDS.toNanos(2);
        }
        Arrays.sort(lateness);
        assertThat(lateness[0]).as("never early").isGreaterThanOrEqualTo(0);
        // OS timers on Windows would be ~13.6 ms late here (15.6 ms tick for a 2 ms delay)
        assertThat(lateness[lateness.length / 2]).as("median lateness").isLessThan(TimeUnit.MILLISECONDS.toNanos(1));
    }

    @Test
    void runsTasksInDeadlineOrderEvenWhenAShorterOneArrivesWhileParked() throws InterruptedException {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        scheduler.schedule(() -> {
            order.add("long");
            done.countDown();
        }, 200, TimeUnit.MILLISECONDS);
        Thread.sleep(20); // the timer thread is now parked waiting for the 200 ms task
        long start = System.nanoTime();
        scheduler.schedule(() -> {
            order.add("short");
            done.countDown();
        }, 5, TimeUnit.MILLISECONDS);
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("short", "long");
        assertThat(System.nanoTime() - start).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(150));
    }
}
