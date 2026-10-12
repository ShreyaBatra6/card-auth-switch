package com.cardswitch.server.issuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs tasks after a delay with sub-millisecond precision, for simulating issuer network latency.
 * <p>
 * OS timers are too coarse for this on Windows: timed waits are rounded up to the 15.6 ms timer tick, and
 * Windows 11 honors a process's request for 1 ms resolution for only a few seconds when the process has no
 * visible window, so a simulated 2 ms latency turns into ~15 ms. This thread parks while the next deadline is
 * far away and spins for the last stretch, so it fires on time regardless of OS timer resolution.
 * <p>
 * The cost is up to one CPU core while simulated responses are pending. That is acceptable for a simulator of
 * remote hosts, which in a real deployment would not run in the switch's JVM at all.
 */
public final class PreciseDelayScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PreciseDelayScheduler.class);

    /** Longer than the coarsest OS timer tick (15.6 ms), so a coarse park never overshoots a deadline. */
    private static final long SPIN_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private record Task(long deadline, long sequence, Runnable action) implements Comparable<Task> {

        @Override
        public int compareTo(Task other) {
            int byDeadline = Long.compare(deadline, other.deadline);
            return byDeadline != 0 ? byDeadline : Long.compare(sequence, other.sequence);
        }
    }

    private final Queue<Task> inbox = new ConcurrentLinkedQueue<>();
    private final PriorityQueue<Task> pending = new PriorityQueue<>(); // owned by the timer thread
    private final AtomicLong sequence = new AtomicLong();
    private final Thread thread;
    private volatile boolean idle;
    private volatile boolean running = true;

    public PreciseDelayScheduler(String threadName) {
        thread = Thread.ofPlatform().name(threadName).daemon(true).start(this::run);
    }

    public void schedule(Runnable action, long delay, TimeUnit unit) {
        inbox.add(new Task(System.nanoTime() + unit.toNanos(delay), sequence.incrementAndGet(), action));
        if (idle) {
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
    }

    private void run() {
        while (running) {
            for (Task task; (task = inbox.poll()) != null; ) {
                pending.add(task);
            }
            long now = System.nanoTime();
            while (!pending.isEmpty() && pending.peek().deadline() <= now) {
                try {
                    pending.poll().action().run();
                } catch (RuntimeException e) {
                    log.error("Delayed task failed", e);
                }
            }
            long gap = pending.isEmpty() ? Long.MAX_VALUE : pending.peek().deadline() - System.nanoTime();
            if (gap <= SPIN_WINDOW_NANOS) {
                Thread.onSpinWait();
                continue;
            }
            // Park until the spin window opens, or indefinitely if nothing is pending. schedule() unparks us if a
            // new task arrives meanwhile (it may be due sooner); the inbox re-check closes the race with it.
            idle = true;
            if (inbox.isEmpty() && running) {
                if (pending.isEmpty()) {
                    LockSupport.park(this);
                } else {
                    LockSupport.parkNanos(this, gap - SPIN_WINDOW_NANOS);
                }
            }
            idle = false;
        }
    }
}
