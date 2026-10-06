package com.cardswitch.loadgen;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.card.Pans;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static com.cardswitch.iso8583.Field.ACQUIRER_ID;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.CURRENCY_CODE;
import static com.cardswitch.iso8583.Field.EXPIRATION_DATE;
import static com.cardswitch.iso8583.Field.LOCAL_DATE;
import static com.cardswitch.iso8583.Field.LOCAL_TIME;
import static com.cardswitch.iso8583.Field.MERCHANT_ID;
import static com.cardswitch.iso8583.Field.MERCHANT_NAME_LOCATION;
import static com.cardswitch.iso8583.Field.MERCHANT_TYPE;
import static com.cardswitch.iso8583.Field.NETWORK_MANAGEMENT_CODE;
import static com.cardswitch.iso8583.Field.ORIGINAL_DATA_ELEMENTS;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.POS_CONDITION_CODE;
import static com.cardswitch.iso8583.Field.POS_ENTRY_MODE;
import static com.cardswitch.iso8583.Field.PROCESSING_CODE;
import static com.cardswitch.iso8583.Field.RRN;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;

/**
 * Builds realistic acquirer traffic: random test cards from the configured BINs, log-normally distributed purchase
 * amounts, assorted merchants, and a configurable share of bad card numbers, expired cards and unknown BINs.
 * STAN and terminal ID are filled in by {@link IsoClient} per connection. Thread-safe.
 */
final class TransactionFactory {

    /** Percentages (0-100) of deliberately invalid requests. */
    record Mix(double badLuhnPct, double expiredPct, double unknownBinPct) {
    }

    private record Merchant(String id, String mcc, String nameLocation) {

        /** Field 43 layout: name (23) + city (13) + state (2) + country (2) = 40 characters. */
        Merchant(String id, String mcc, String name, String city, String state) {
            this(id, mcc, "%-23.23s%-13.13s%-2.2s%-2.2s".formatted(name, city, state, "US"));
        }
    }

    private static final List<Merchant> MERCHANTS = List.of(
            new Merchant("000000000100001", "5411", "GREENLEAF GROCERY", "SEATTLE", "WA"),
            new Merchant("000000000100002", "5812", "PIKE STREET DINER", "SEATTLE", "WA"),
            new Merchant("000000000100003", "5541", "NORTHGATE FUEL", "SEATTLE", "WA"),
            new Merchant("000000000100004", "5732", "BYTE ELECTRONICS", "BELLEVUE", "WA"),
            new Merchant("000000000100005", "5999", "CORNER MARKET", "TACOMA", "WA"));
    private static final String[] ENTRY_MODES = {"051", "071", "010"}; // chip, contactless, manual key entry
    private static final String UNKNOWN_BIN = "499999";
    private static final double MEDIAN_AMOUNT_CENTS = 4_000;
    private static final double AMOUNT_SIGMA = 1.0;
    private static final long MAX_AMOUNT_CENTS = 500_000;
    private static final DateTimeFormatter TRANSMISSION = DateTimeFormatter.ofPattern("MMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyMM");

    private final List<String> bins;
    private final int accounts;
    private final Mix mix;
    private final String validExpiry = YearMonth.now(ZoneOffset.UTC).plusYears(3).format(EXPIRY);
    private final String expiredExpiry = YearMonth.now(ZoneOffset.UTC).minusMonths(2).format(EXPIRY);
    /** RRNs must be unique across runs while the switch's duplicate/hold caches remember them. */
    private final AtomicLong rrnSequence = new AtomicLong((System.currentTimeMillis() / 1000 % 100_000) * 10_000_000L);

    TransactionFactory(List<String> bins, int accounts, Mix mix) {
        this.bins = List.copyOf(bins);
        this.accounts = accounts;
        this.mix = mix;
    }

    IsoMessage authorization() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double roll = random.nextDouble(100);
        String pan;
        String expiry = validExpiry;
        if (roll < mix.unknownBinPct()) {
            pan = Pans.testCard(UNKNOWN_BIN, random.nextInt(accounts));
        } else {
            pan = Pans.testCard(bins.get(random.nextInt(bins.size())), random.nextInt(accounts));
            if (roll < mix.unknownBinPct() + mix.badLuhnPct()) {
                char last = pan.charAt(pan.length() - 1);
                pan = pan.substring(0, pan.length() - 1) + (char) ('0' + (last - '0' + 1) % 10);
            } else if (roll < mix.unknownBinPct() + mix.badLuhnPct() + mix.expiredPct()) {
                expiry = expiredExpiry;
            }
        }
        long amount = Math.min(MAX_AMOUNT_CENTS,
                Math.max(100, Math.round(MEDIAN_AMOUNT_CENTS * Math.exp(AMOUNT_SIGMA * random.nextGaussian()))));
        return authorization(pan, amount, expiry);
    }

    IsoMessage authorization(String pan, long amountCents, String expiry) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Merchant merchant = MERCHANTS.get(random.nextInt(MERCHANTS.size()));
        String transmission = TRANSMISSION.format(Instant.now());
        return new IsoMessage(Mti.AUTHORIZATION_REQUEST)
                .set(PAN, pan)
                .set(PROCESSING_CODE, "000000")
                .set(AMOUNT, "%012d".formatted(amountCents))
                .set(TRANSMISSION_DATE_TIME, transmission)
                .set(LOCAL_TIME, transmission.substring(4))
                .set(LOCAL_DATE, transmission.substring(0, 4))
                .set(EXPIRATION_DATE, expiry)
                .set(MERCHANT_TYPE, merchant.mcc())
                .set(POS_ENTRY_MODE, ENTRY_MODES[random.nextInt(ENTRY_MODES.length)])
                .set(POS_CONDITION_CODE, "00")
                .set(ACQUIRER_ID, "100001")
                .set(RRN, nextRrn())
                .set(MERCHANT_ID, merchant.id())
                .set(MERCHANT_NAME_LOCATION, merchant.nameLocation())
                .set(CURRENCY_CODE, "840");
    }

    /** A full reversal of an approved authorization, matched by its RRN. */
    IsoMessage reversalOf(IsoMessage original) {
        String acquirer = original.get(ACQUIRER_ID);
        return original.copy()
                .setMti(Mti.REVERSAL_REQUEST)
                .remove(EXPIRATION_DATE)
                .set(TRANSMISSION_DATE_TIME, TRANSMISSION.format(Instant.now()))
                .set(ORIGINAL_DATA_ELEMENTS, original.mti() + original.get(STAN) + original.get(TRANSMISSION_DATE_TIME)
                        + "0".repeat(11 - acquirer.length()) + acquirer + "0".repeat(11));
    }

    IsoMessage echo() {
        return new IsoMessage(Mti.NETWORK_MANAGEMENT_REQUEST)
                .set(TRANSMISSION_DATE_TIME, TRANSMISSION.format(Instant.now()))
                .set(NETWORK_MANAGEMENT_CODE, "301");
    }

    private String nextRrn() {
        return "%012d".formatted(rrnSequence.incrementAndGet() % 1_000_000_000_000L);
    }
}
