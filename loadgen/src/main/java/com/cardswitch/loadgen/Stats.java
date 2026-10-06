package com.cardswitch.loadgen;

import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.ResponseSource;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Results of one load stage. Latencies are recorded in microseconds into HdrHistogram recorders (lock-free for the
 * recording threads); the reporter thread pulls an interval histogram every second.
 */
final class Stats {

    /** Snapshot of one reporting interval. */
    record Interval(long sent, long received, Histogram latency, long approved, long standIn, long failed) {
    }

    private final Recorder responseTime = new Recorder(3);
    private final Recorder serviceTime = new Recorder(3);
    private final Histogram totalResponseTime = new Histogram(3);
    private final Histogram totalServiceTime = new Histogram(3);

    private final LongAdder sent = new LongAdder();
    private final LongAdder received = new LongAdder();
    private final LongAdder approved = new LongAdder();
    private final LongAdder standIn = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder reversalsSent = new LongAdder();
    private final Map<String, LongAdder> responseCodes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> reversalCodes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> sources = new ConcurrentHashMap<>();

    private long lastSent;
    private long lastReceived;
    private long lastApproved;
    private long lastStandIn;
    private long lastFailed;

    void sent() {
        sent.increment();
    }

    void received(long responseTimeNanos, long serviceTimeNanos, String responseCode, String field44) {
        responseTime.recordValue(Math.max(0, responseTimeNanos / 1_000));
        serviceTime.recordValue(Math.max(0, serviceTimeNanos / 1_000));
        received.increment();
        String code = responseCode == null ? "??" : responseCode;
        responseCodes.computeIfAbsent(code, k -> new LongAdder()).increment();
        if (ResponseCode.APPROVED.code().equals(code)) {
            approved.increment();
        }
        ResponseSource source = ResponseSource.fromField44(field44);
        sources.computeIfAbsent(source == null ? "unknown" : source.description(), k -> new LongAdder()).increment();
        if (source != null && source.isStandIn()) {
            standIn.increment();
        }
    }

    void failed(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        (cause instanceof TimeoutException ? timeouts : errors).increment();
    }

    void reversalSent() {
        reversalsSent.increment();
    }

    void reversalReceived(String responseCode) {
        reversalCodes.computeIfAbsent(responseCode == null ? "??" : responseCode, k -> new LongAdder()).increment();
    }

    /** Called once per second by the reporter thread. */
    synchronized Interval interval() {
        Histogram latency = responseTime.getIntervalHistogram();
        totalResponseTime.add(latency);
        totalServiceTime.add(serviceTime.getIntervalHistogram());
        long s = sent.sum();
        long r = received.sum();
        long a = approved.sum();
        long si = standIn.sum();
        long f = timeouts.sum() + errors.sum();
        Interval interval = new Interval(s - lastSent, r - lastReceived, latency, a - lastApproved, si - lastStandIn, f - lastFailed);
        lastSent = s;
        lastReceived = r;
        lastApproved = a;
        lastStandIn = si;
        lastFailed = f;
        return interval;
    }

    /** Folds in anything recorded since the last interval and returns the whole-stage histograms. */
    synchronized Histogram[] totals() {
        totalResponseTime.add(responseTime.getIntervalHistogram());
        totalServiceTime.add(serviceTime.getIntervalHistogram());
        return new Histogram[]{totalResponseTime.copy(), totalServiceTime.copy()};
    }

    long sentCount() {
        return sent.sum();
    }

    long receivedCount() {
        return received.sum();
    }

    long approvedCount() {
        return approved.sum();
    }

    long standInCount() {
        return standIn.sum();
    }

    long timeoutCount() {
        return timeouts.sum();
    }

    long errorCount() {
        return errors.sum();
    }

    long reversalsSentCount() {
        return reversalsSent.sum();
    }

    Map<String, Long> responseCodes() {
        return snapshot(responseCodes);
    }

    Map<String, Long> reversalCodes() {
        return snapshot(reversalCodes);
    }

    Map<String, Long> sources() {
        return snapshot(sources);
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> counters) {
        Map<String, Long> copy = new TreeMap<>();
        counters.forEach((k, v) -> copy.put(k, v.sum()));
        return copy;
    }
}
