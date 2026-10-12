package com.cardswitch.server.issuer;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.iso8583.card.Pans;
import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.core.Responses;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.AUTH_ID_RESPONSE;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;
import static com.cardswitch.iso8583.Field.RRN;

/**
 * A mock issuer authorization host: cardholder accounts with balances, holds placed by approvals, and a configurable
 * response latency so slow or dead issuers can be simulated.
 * <p>
 * A message is applied the moment it arrives; only the response is delayed. A response that arrives after the
 * switch's timeout therefore reflects a decision the issuer really made, which is what makes late approvals need
 * reversing.
 * <p>
 * Test cards are {@code Pans.testCard(bin, i)} for {@code i < accounts}. Every 100th card (index ending in 07) is
 * blocked, and every 250th (index % 250 == 13) is reported stolen.
 */
public final class SimulatedIssuer implements IssuerClient {

    /** z-score of the 99th percentile of a standard normal distribution. */
    private static final double Z_99 = 2.3263;
    private static final long MAX_LATENCY_NANOS = TimeUnit.SECONDS.toNanos(30);

    /**
     * Response latency follows a log-normal distribution fitted to the given median and 99th percentile, which is a
     * reasonable shape for network + host service time. {@code available = false} drops every message unanswered.
     */
    public record Behavior(Duration medianLatency, Duration p99Latency, boolean available) {

        long sampleLatencyNanos() {
            long median = medianLatency.toNanos();
            if (median <= 0) {
                return 0;
            }
            long p99 = p99Latency.toNanos();
            if (p99 <= median) {
                return median;
            }
            double sigma = Math.log((double) p99 / median) / Z_99;
            double sample = median * Math.exp(sigma * ThreadLocalRandom.current().nextGaussian());
            return (long) Math.min(sample, MAX_LATENCY_NANOS);
        }
    }

    enum CardStatus { ACTIVE, BLOCKED, STOLEN }

    private static final class Account {

        final CardStatus status;
        final AtomicLong available;

        Account(CardStatus status, long balance) {
            this.status = status;
            this.available = new AtomicLong(balance);
        }

        boolean debit(long amount) {
            long current;
            do {
                current = available.get();
                if (current < amount) {
                    return false;
                }
            } while (!available.compareAndSet(current, current - amount));
            return true;
        }
    }

    private record Hold(String pan, long amount) {
    }

    private final String issuerId;
    private final String bin;
    private final int accountCount;
    private final Map<String, Account> accounts;
    /** Approved authorizations by RRN, so reversals and advices can be matched to them. */
    private final Cache<String, Hold> holds;
    private final PreciseDelayScheduler scheduler;
    private final AtomicLong approvals = new AtomicLong();
    private volatile Behavior behavior;

    public SimulatedIssuer(String issuerId, String bin, SwitchProperties.Simulator config,
                           PreciseDelayScheduler scheduler) {
        this.issuerId = issuerId;
        this.bin = bin;
        this.accountCount = config.accounts();
        this.scheduler = scheduler;
        this.behavior = new Behavior(config.medianLatency(), config.p99Latency(), true);
        this.holds = Caffeine.newBuilder().maximumSize(config.holdCapacity()).build();
        this.accounts = new HashMap<>(config.accounts() * 4 / 3 + 1);

        SplittableRandom random = new SplittableRandom(issuerId.hashCode());
        long spread = config.maxBalance() - config.minBalance() + 1;
        for (int i = 0; i < config.accounts(); i++) {
            CardStatus status = i % 250 == 13 ? CardStatus.STOLEN : i % 100 == 7 ? CardStatus.BLOCKED : CardStatus.ACTIVE;
            long balance = config.minBalance() + (spread > 1 ? random.nextLong(spread) : 0);
            accounts.put(Pans.testCard(bin, i), new Account(status, balance));
        }
    }

    @Override
    public CompletableFuture<IsoMessage> send(IsoMessage message) {
        Behavior current = behavior;
        if (!current.available()) {
            return new CompletableFuture<>(); // host down: the message is lost and never answered
        }
        IsoMessage response = process(message);
        long latency = current.sampleLatencyNanos();
        if (latency == 0) {
            return CompletableFuture.completedFuture(response);
        }
        CompletableFuture<IsoMessage> future = new CompletableFuture<>();
        scheduler.schedule(() -> future.complete(response), latency, TimeUnit.NANOSECONDS);
        return future;
    }

    private IsoMessage process(IsoMessage message) {
        return switch (message.mti()) {
            case Mti.AUTHORIZATION_REQUEST -> authorize(message);
            case Mti.AUTHORIZATION_ADVICE -> recordAdvice(message);
            case Mti.REVERSAL_REQUEST -> reverse(message);
            default -> Responses.replyTo(message, ResponseCode.INVALID_TRANSACTION);
        };
    }

    private IsoMessage authorize(IsoMessage request) {
        IsoMessage reply = Responses.replyTo(request);
        Account account = accounts.get(request.get(PAN));
        long amount = Long.parseLong(request.get(AMOUNT));
        ResponseCode code;
        if (account == null) {
            code = ResponseCode.INVALID_CARD_NUMBER;
        } else if (account.status == CardStatus.BLOCKED) {
            code = ResponseCode.DO_NOT_HONOR;
        } else if (account.status == CardStatus.STOLEN) {
            code = ResponseCode.STOLEN_CARD;
        } else if (!account.debit(amount)) {
            code = ResponseCode.INSUFFICIENT_FUNDS;
        } else {
            holds.put(request.get(RRN), new Hold(request.get(PAN), amount));
            reply.set(AUTH_ID_RESPONSE, String.format("%06d", approvals.incrementAndGet() % 1_000_000));
            code = ResponseCode.APPROVED;
        }
        return reply.set(RESPONSE_CODE, code.code());
    }

    /**
     * 0120: the switch approved a transaction in stand-in. Post it unless this host already approved the same RRN
     * itself (its response arrived too late), so the cardholder is charged exactly once. Stand-in approvals are
     * posted even if they overdraw the account; that is the risk the issuer accepts by setting stand-in limits.
     */
    private IsoMessage recordAdvice(IsoMessage advice) {
        Account account = accounts.get(advice.get(PAN));
        if (account != null && Responses.isApproved(advice)) {
            long amount = Long.parseLong(advice.get(AMOUNT));
            if (holds.asMap().putIfAbsent(advice.get(RRN), new Hold(advice.get(PAN), amount)) == null) {
                account.available.addAndGet(-amount);
            }
        }
        return Responses.replyTo(advice, ResponseCode.APPROVED);
    }

    private IsoMessage reverse(IsoMessage reversal) {
        String rrn = reversal.get(RRN);
        Hold hold = holds.asMap().remove(rrn);
        if (hold == null || !hold.pan().equals(reversal.get(PAN))) {
            if (hold != null) {
                holds.put(rrn, hold);
            }
            return Responses.replyTo(reversal, ResponseCode.UNABLE_TO_LOCATE_ORIGINAL);
        }
        accounts.get(hold.pan()).available.addAndGet(hold.amount());
        return Responses.replyTo(reversal, ResponseCode.APPROVED);
    }

    public String issuerId() {
        return issuerId;
    }

    public String bin() {
        return bin;
    }

    public int accountCount() {
        return accountCount;
    }

    public Behavior behavior() {
        return behavior;
    }

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    /** Available balance in minor units; for tests and demos. */
    public long availableBalance(String pan) {
        Account account = accounts.get(pan);
        if (account == null) {
            throw new IllegalArgumentException("Unknown card " + Pans.mask(pan));
        }
        return account.available.get();
    }
}
