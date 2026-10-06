package com.cardswitch.loadgen;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.ResponseSource;
import com.cardswitch.iso8583.card.Pans;

import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;

/**
 * Acquirer simulator for the authorization switch.
 * <pre>
 *   run    open-model load test with per-second and per-stage latency reports
 *   send   send a single transaction and print both messages field by field
 *   cards  list test card numbers known to the simulated issuers
 * </pre>
 */
public final class LoadGenerator {

    private static final String USAGE = """
            Usage: java -jar loadgen.jar <command> [options]

            run     Open-model load test
              --host 127.0.0.1 --port 8583      switch address
              --tps 1000,2000,4000              target rate per stage (comma separated)
              --duration 30                     seconds per stage
              --warmup 10                       unmeasured warm-up seconds at the first stage's rate
              --connections 8 --io-threads 2    TCP connections and Netty threads
              --bins 400010,400020,400030       BINs to draw cards from
              --accounts 100000                 cards per BIN
              --bad-luhn-pct 0.5 --expired-pct 0.5 --unknown-bin-pct 0.2   share of invalid traffic
              --reversal-pct 0                  share of approvals followed by an 0400 reversal
              --timeout-ms 5000                 client-side timeout
              --chaos 20:cedar:available=false,40:cedar:available=true   fault injection schedule
              --admin http://127.0.0.1:8080     switch admin API (for --chaos)
              --csv results.csv --summary summary.md                    output files

            send    Send one transaction and print request and response
              --type auth|echo                  message type (default auth)
              --pan <pan> | --bin 400010 --index 0   card (default Northwind card #0)
              --amount 25.00 --expiry YYMM
              --reverse                         after an approval, send a reversal for it

            cards   List test cards
              --bin 400010 --count 10
            """;

    private static final Set<String> RUN_OPTIONS = Set.of("host", "port", "tps", "duration", "warmup", "connections",
            "io-threads", "bins", "accounts", "bad-luhn-pct", "expired-pct", "unknown-bin-pct", "reversal-pct",
            "timeout-ms", "chaos", "admin", "csv", "summary");
    private static final Set<String> SEND_OPTIONS = Set.of("host", "port", "type", "pan", "bin", "index", "amount",
            "expiry", "reverse");
    private static final Set<String> CARDS_OPTIONS = Set.of("bin", "count");

    private LoadGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        Options options = Options.parse(args, 1);
        switch (args[0]) {
            case "run" -> run(options);
            case "send" -> send(options);
            case "cards" -> cards(options);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.out.print(USAGE);
                System.exit(2);
            }
        }
    }

    private static void run(Options options) throws Exception {
        options.rejectUnknown(RUN_OPTIONS);
        TransactionFactory factory = new TransactionFactory(
                options.list("bins", "400010,400020,400030"),
                options.integer("accounts", 100_000),
                new TransactionFactory.Mix(
                        options.decimal("bad-luhn-pct", 0.5),
                        options.decimal("expired-pct", 0.5),
                        options.decimal("unknown-bin-pct", 0.2)));
        List<String> chaosSpecs = options.list("chaos", null);
        Chaos chaos = chaosSpecs.isEmpty() ? null : new Chaos(options.string("admin", "http://127.0.0.1:8080"), chaosSpecs);
        String csv = options.string("csv", null);
        String summary = options.string("summary", null);

        new LoadTest(new LoadTest.Config(
                options.string("host", "127.0.0.1"),
                options.integer("port", 8583),
                options.integer("connections", 8),
                options.integer("io-threads", 2),
                options.list("tps", "1000").stream().map(Integer::parseInt).toList(),
                options.integer("duration", 30),
                options.integer("warmup", 10),
                options.integer("timeout-ms", 5_000),
                options.decimal("reversal-pct", 0),
                factory,
                chaos,
                csv == null ? null : Path.of(csv),
                summary == null ? null : Path.of(summary))).run();
    }

    private static void send(Options options) throws Exception {
        options.rejectUnknown(SEND_OPTIONS);
        TransactionFactory factory = new TransactionFactory(List.of("400010"), 1, new TransactionFactory.Mix(0, 0, 0));
        IsoMessage request;
        if (options.string("type", "auth").equals("echo")) {
            request = factory.echo();
        } else {
            String pan = options.string("pan", null);
            if (pan == null) {
                pan = Pans.testCard(options.string("bin", "400010"), options.integer("index", 0));
            }
            long cents = Math.round(options.decimal("amount", 25.00) * 100);
            String expiry = options.string("expiry",
                    YearMonth.now(ZoneOffset.UTC).plusYears(3).format(DateTimeFormatter.ofPattern("yyMM")));
            request = factory.authorization(pan, cents, expiry);
        }

        try (IsoClient client = new IsoClient(options.string("host", "127.0.0.1"), options.integer("port", 8583), 1, 1)) {
            IsoMessage response = exchange(client, request);
            if (options.flag("reverse") && ResponseCode.APPROVED.code().equals(response.get(RESPONSE_CODE))) {
                exchange(client, factory.reversalOf(request));
            }
        }
    }

    private static IsoMessage exchange(IsoClient client, IsoMessage request) throws Exception {
        long start = System.nanoTime();
        var pending = client.send(request); // assigns STAN and terminal ID, so print afterwards
        System.out.println(">>> Request");
        System.out.print(request.dump(client.spec()));
        IsoMessage response = pending.get(10, TimeUnit.SECONDS);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("<<< Response (%.2f ms)%n", ms);
        System.out.print(response.dump(client.spec()));
        ResponseSource source = ResponseSource.fromField44(response.get(ADDITIONAL_RESPONSE_DATA));
        System.out.printf("=== %s %s%s%n%n", response.get(RESPONSE_CODE), ResponseCode.describe(response.get(RESPONSE_CODE)),
                source == null ? "" : " (decided by: " + source.description() + ")");
        return response;
    }

    private static void cards(Options options) {
        options.rejectUnknown(CARDS_OPTIONS);
        String bin = options.string("bin", "400010");
        int count = options.integer("count", 10);
        for (int i = 0; i < count; i++) {
            String note = i % 250 == 13 ? "  (reported stolen -> 43)" : i % 100 == 7 ? "  (blocked -> 05)" : "";
            System.out.printf("%5d  %s%s%n", i, Pans.testCard(bin, i), note);
        }
    }
}
