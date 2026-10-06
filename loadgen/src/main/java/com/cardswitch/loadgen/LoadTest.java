package com.cardswitch.loadgen;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import org.HdrHistogram.Histogram;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;

/**
 * Open-model load test: requests are sent on a fixed schedule ({@code 1/tps} apart) whether or not earlier ones have
 * been answered, the way independent card terminals behave. Latency is measured from each request's
 * <em>scheduled</em> send time, so if the generator or the switch falls behind, the queueing delay is counted
 * instead of silently lowering the request rate ("coordinated omission").
 */
final class LoadTest {

    record Config(String host, int port, int connections, int ioThreads, List<Integer> stagesTps, int stageSeconds,
                  int warmupSeconds, int timeoutMs, double reversalPct, TransactionFactory factory, Chaos chaos,
                  Path csv, Path summary) {
    }

    private record StageResult(int targetTps, int seconds, double achievedTps, Histogram latency, Histogram service,
                               Stats stats) {
    }

    private final Config config;
    private final ScheduledExecutorService background = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "loadgen-reporter");
        t.setDaemon(true);
        return t;
    });
    private final List<String> csvRows = new ArrayList<>();
    private int elapsedSeconds;

    LoadTest(Config config) {
        this.config = config;
    }

    void run() throws Exception {
        System.out.printf("Connecting %d connection(s) to %s:%d%n", config.connections(), config.host(), config.port());
        try (IsoClient client = new IsoClient(config.host(), config.port(), config.connections(), config.ioThreads())) {
            IsoMessage echo = client.send(config.factory().echo()).get(5, TimeUnit.SECONDS);
            System.out.printf("Echo test: %s%n", echo.get(RESPONSE_CODE));

            ScheduledFuture<?> sweeper = background.scheduleAtFixedRate(
                    () -> client.expire(TimeUnit.MILLISECONDS.toNanos(config.timeoutMs())), 250, 250, TimeUnit.MILLISECONDS);

            if (config.warmupSeconds() > 0) {
                System.out.printf("%nWarm-up: %d TPS for %ds (not measured)%n", config.stagesTps().getFirst(), config.warmupSeconds());
                runStage(client, config.stagesTps().getFirst(), config.warmupSeconds(), false, false);
            }
            List<StageResult> results = new ArrayList<>();
            boolean first = true;
            for (int tps : config.stagesTps()) {
                System.out.printf("%nStage: %,d TPS for %ds%n", tps, config.stageSeconds());
                results.add(runStage(client, tps, config.stageSeconds(), true, first));
                printStage(results.getLast());
                first = false;
            }
            sweeper.cancel(false);
            if (client.unmatchedResponses() > 0) {
                System.out.printf("Unmatched responses: %d%n", client.unmatchedResponses());
            }
            String table = summaryTable(results);
            System.out.println();
            System.out.println(table);
            writeOutputs(table);
        } finally {
            background.shutdownNow();
        }
    }

    private StageResult runStage(IsoClient client, int tps, int seconds, boolean measured, boolean withChaos) {
        Stats stats = new Stats();
        long period = 1_000_000_000L / tps;
        long start = System.nanoTime() + 1_000_000;
        long end = start + TimeUnit.SECONDS.toNanos(seconds);

        int[] second = {0};
        ScheduledFuture<?> reporter = background.scheduleAtFixedRate(() -> {
            second[0]++;
            report(second[0], tps, stats.interval(), client.inFlight(), measured);
        }, 1, 1, TimeUnit.SECONDS);
        List<ScheduledFuture<?>> chaos = new ArrayList<>();
        if (withChaos && config.chaos() != null) {
            for (Chaos.Event event : config.chaos().events()) {
                chaos.add(background.schedule(() -> config.chaos().apply(event), event.atSecond(), TimeUnit.SECONDS));
            }
        }

        long sent = 0;
        while (true) {
            long now = System.nanoTime();
            if (now >= end) {
                break;
            }
            long due = (now - start) / period + 1;
            while (sent < due) {
                send(client, stats, start + sent * period);
                sent++;
            }
            long wait = start + sent * period - System.nanoTime();
            if (wait > 2_000_000) {
                LockSupport.parkNanos(wait - 1_000_000); // coarse sleep, then spin for precision
            } else {
                Thread.onSpinWait();
            }
        }

        long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs() + 1_000L);
        while (stats.receivedCount() + stats.timeoutCount() + stats.errorCount() < stats.sentCount()
                && System.nanoTime() < drainDeadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        reporter.cancel(false);
        chaos.forEach(f -> f.cancel(false));
        if (measured) {
            elapsedSeconds += seconds;
        }
        Histogram[] totals = stats.totals();
        return new StageResult(tps, seconds, stats.receivedCount() / (double) seconds, totals[0], totals[1], stats);
    }

    private void send(IsoClient client, Stats stats, long intendedNanos) {
        IsoMessage request = config.factory().authorization();
        stats.sent();
        long sentAt = System.nanoTime();
        client.send(request).whenComplete((response, error) -> {
            long now = System.nanoTime();
            if (error != null) {
                stats.failed(error);
                return;
            }
            stats.received(now - intendedNanos, now - sentAt, response.get(RESPONSE_CODE), response.get(ADDITIONAL_RESPONSE_DATA));
            if (config.reversalPct() > 0 && ResponseCode.APPROVED.code().equals(response.get(RESPONSE_CODE))
                    && ThreadLocalRandom.current().nextDouble(100) < config.reversalPct()) {
                stats.reversalSent();
                client.send(config.factory().reversalOf(request)).whenComplete((reversal, reversalError) ->
                        stats.reversalReceived(reversalError == null ? reversal.get(RESPONSE_CODE) : "timeout/error"));
            }
        });
    }

    private void report(int second, int tps, Stats.Interval interval, int inFlight, boolean measured) {
        Histogram h = interval.latency();
        double approvedPct = pct(interval.approved(), interval.received());
        double standInPct = pct(interval.standIn(), interval.received());
        System.out.printf(Locale.US, "[%3ds] target %,6d/s | sent %,6d | answered %,6d | p50 %6.2f ms | p99 %7.2f ms | max %7.2f ms | approved %5.1f%% | stand-in %5.1f%% | failed %d | in flight %d%n",
                second, tps, interval.sent(), interval.received(), ms(h, 50), ms(h, 99), maxMs(h), approvedPct, standInPct,
                interval.failed(), inFlight);
        if (measured) {
            csvRows.add(String.format(Locale.US, "%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%d",
                    elapsedSeconds + second, tps, interval.sent(), interval.received(), ms(h, 50), ms(h, 90), ms(h, 99),
                    maxMs(h), approvedPct, standInPct, interval.failed()));
        }
    }

    private static void printStage(StageResult r) {
        Stats s = r.stats();
        System.out.printf(Locale.US, "%n  Requests    %,d sent, %,d answered, %,d timed out, %,d errors%n",
                s.sentCount(), s.receivedCount(), s.timeoutCount(), s.errorCount());
        System.out.printf(Locale.US, "  Throughput  %,.1f TPS answered (target %,d)%n", r.achievedTps(), r.targetTps());
        System.out.printf(Locale.US, "  Latency     p50 %.2f | p90 %.2f | p99 %.2f | p99.9 %.2f | max %.2f ms  (from scheduled send time)%n",
                ms(r.latency(), 50), ms(r.latency(), 90), ms(r.latency(), 99), ms(r.latency(), 99.9), maxMs(r.latency()));
        System.out.printf(Locale.US, "  Service     p50 %.2f | p90 %.2f | p99 %.2f | p99.9 %.2f | max %.2f ms  (from actual send time)%n",
                ms(r.service(), 50), ms(r.service(), 90), ms(r.service(), 99), ms(r.service(), 99.9), maxMs(r.service()));
        System.out.println("  Responses   " + breakdown(s.responseCodes(), s.receivedCount(), true));
        System.out.println("  Decided by  " + breakdown(s.sources(), s.receivedCount(), false));
        if (s.reversalsSentCount() > 0) {
            System.out.printf("  Reversals   %,d sent: %s%n", s.reversalsSentCount(), breakdown(s.reversalCodes(), s.reversalsSentCount(), true));
        }
    }

    private static String summaryTable(List<StageResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Target TPS | Achieved TPS | p50 (ms) | p90 (ms) | p99 (ms) | p99.9 (ms) | max (ms) | Approved | Stand-in | Timeouts/errors |\n");
        sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (StageResult r : results) {
            Stats s = r.stats();
            sb.append(String.format(Locale.US, "| %,d | %,.0f | %.2f | %.2f | %.2f | %.2f | %.2f | %.1f%% | %.2f%% | %d |%n",
                    r.targetTps(), r.achievedTps(), ms(r.latency(), 50), ms(r.latency(), 90), ms(r.latency(), 99),
                    ms(r.latency(), 99.9), maxMs(r.latency()), pct(s.approvedCount(), s.receivedCount()),
                    pct(s.standInCount(), s.receivedCount()), s.timeoutCount() + s.errorCount()));
        }
        return sb.toString();
    }

    private void writeOutputs(String table) throws IOException {
        if (config.csv() != null) {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(config.csv()))) {
                out.println("second,target_tps,sent,answered,p50_ms,p90_ms,p99_ms,max_ms,approved_pct,stand_in_pct,failed");
                csvRows.forEach(out::println);
            }
            System.out.println("Per-second results written to " + config.csv());
        }
        if (config.summary() != null) {
            Files.writeString(config.summary(), table);
            System.out.println("Summary table written to " + config.summary());
        }
    }

    private static String breakdown(Map<String, Long> counts, long total, boolean describeCodes) {
        StringBuilder sb = new StringBuilder();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    if (!sb.isEmpty()) {
                        sb.append(" | ");
                    }
                    String label = describeCodes ? e.getKey() + " " + ResponseCode.describe(e.getKey()) : e.getKey();
                    sb.append(String.format(Locale.US, "%s %.2f%%", label, pct(e.getValue(), total)));
                });
        return sb.toString();
    }

    private static double ms(Histogram h, double percentile) {
        return h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(percentile) / 1_000.0;
    }

    private static double maxMs(Histogram h) {
        return h.getTotalCount() == 0 ? 0 : h.getMaxValue() / 1_000.0;
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }
}
