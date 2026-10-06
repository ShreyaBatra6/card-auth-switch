package com.cardswitch.server;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.card.Pans;
import com.cardswitch.server.issuer.SimulatedIssuer;
import com.cardswitch.server.net.IsoServer;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerRegistry;
import com.cardswitch.server.stip.StoreAndForwardQueue;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.cardswitch.iso8583.Field.ACQUIRER_ID;
import static com.cardswitch.iso8583.Field.ADDITIONAL_RESPONSE_DATA;
import static com.cardswitch.iso8583.Field.AMOUNT;
import static com.cardswitch.iso8583.Field.AUTH_ID_RESPONSE;
import static com.cardswitch.iso8583.Field.CURRENCY_CODE;
import static com.cardswitch.iso8583.Field.EXPIRATION_DATE;
import static com.cardswitch.iso8583.Field.MERCHANT_ID;
import static com.cardswitch.iso8583.Field.NETWORK_MANAGEMENT_CODE;
import static com.cardswitch.iso8583.Field.PAN;
import static com.cardswitch.iso8583.Field.PROCESSING_CODE;
import static com.cardswitch.iso8583.Field.RESPONSE_CODE;
import static com.cardswitch.iso8583.Field.RRN;
import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;
import static com.cardswitch.iso8583.Field.TRANSMISSION_DATE_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end over TCP: a real Netty server, the full authorization pipeline and the simulated issuers.
 * Test issuers answer instantly and every card starts with a $1,000.00 balance (see application-test.yml).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SwitchIntegrationTest {

    private static final long INITIAL_BALANCE = 100_000;
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger NEXT_CARD = new AtomicInteger(20);

    @Autowired
    IsoServer server;
    @Autowired
    IssuerRegistry issuers;
    @Autowired
    StoreAndForwardQueue storeAndForward;

    TestAcquirer acquirer;

    @BeforeEach
    void connect() {
        acquirer = new TestAcquirer(server.port());
    }

    @AfterEach
    void restoreIssuers() {
        acquirer.close();
        for (Issuer issuer : issuers.all()) {
            simulator(issuer.id()).setBehavior(new SimulatedIssuer.Behavior(Duration.ZERO, Duration.ZERO, true));
            issuer.circuitBreaker().reset();
        }
        await().atMost(Duration.ofSeconds(10)).until(() ->
                issuers.all().stream().allMatch(i -> storeAndForward.depth(i.id()) == 0));
    }

    @Test
    void approvesAValidCardAndDebitsTheAccount() {
        String card = freshCard("400010");
        IsoMessage request = authorization(card, 2_500);

        IsoMessage response = acquirer.send(request);

        assertThat(response.mti()).isEqualTo(Mti.AUTHORIZATION_RESPONSE);
        assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
        assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("5");
        assertThat(response.get(AUTH_ID_RESPONSE)).hasSize(6);
        assertThat(response.get(STAN)).isEqualTo(request.get(STAN));
        assertThat(response.get(RRN)).isEqualTo(request.get(RRN));
        assertThat(response.has(EXPIRATION_DATE)).as("expiry is never echoed").isFalse();
        assertThat(simulator("northwind").availableBalance(card)).isEqualTo(INITIAL_BALANCE - 2_500);
    }

    @Nested
    class SwitchLevelChecks {

        @Test
        void rejectsCardNumberFailingLuhn() {
            String valid = freshCard("400010");
            String invalid = valid.substring(0, 15) + (char) ('0' + (valid.charAt(15) - '0' + 1) % 10);
            assertDeclinedBySwitch(acquirer.send(authorization(invalid, 1_000)), "14");
        }

        @Test
        void rejectsExpiredCard() {
            assertDeclinedBySwitch(acquirer.send(authorization(freshCard("400010"), 1_000).set(EXPIRATION_DATE, "2001")), "54");
        }

        @Test
        void rejectsUnknownBin() {
            assertDeclinedBySwitch(acquirer.send(authorization(Pans.testCard("499999", 1), 1_000)), "15");
        }

        @Test
        void rejectsMissingMandatoryField() {
            assertDeclinedBySwitch(acquirer.send(authorization(freshCard("400010"), 1_000).remove(TERMINAL_ID)), "30");
        }

        @Test
        void rejectsUnsupportedProcessingCode() {
            assertDeclinedBySwitch(acquirer.send(authorization(freshCard("400010"), 1_000).set(PROCESSING_CODE, "310000")), "12");
        }

        @Test
        void rejectsDuplicateTransmission() {
            IsoMessage request = authorization(freshCard("400010"), 1_000);
            assertThat(acquirer.send(request).get(RESPONSE_CODE)).isEqualTo("00");
            assertDeclinedBySwitch(acquirer.send(request), "94");
        }

        @Test
        void enforcesVelocityLimit() {
            String card = freshCard("400020");
            for (int i = 0; i < 5; i++) {
                assertThat(acquirer.send(authorization(card, 100)).get(RESPONSE_CODE)).isEqualTo("00");
            }
            assertDeclinedBySwitch(acquirer.send(authorization(card, 100)), "65");
        }

        private void assertDeclinedBySwitch(IsoMessage response, String code) {
            assertThat(response.get(RESPONSE_CODE)).isEqualTo(code);
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("0");
        }
    }

    @Nested
    class IssuerDecisions {

        @Test
        void declinesInsufficientFunds() {
            assertIssuerDecline(authorization(freshCard("400020"), INITIAL_BALANCE + 1), "51");
        }

        @Test
        void declinesBlockedCard() {
            assertIssuerDecline(authorization(Pans.testCard("400020", 7), 1_000), "05");
        }

        @Test
        void declinesStolenCard() {
            assertIssuerDecline(authorization(Pans.testCard("400020", 13), 1_000), "43");
        }

        private void assertIssuerDecline(IsoMessage request, String code) {
            IsoMessage response = acquirer.send(request);
            assertThat(response.get(RESPONSE_CODE)).isEqualTo(code);
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("5");
            assertThat(response.has(AUTH_ID_RESPONSE)).isFalse();
        }
    }

    @Nested
    class Reversals {

        @Test
        void releasesTheHold() {
            String card = freshCard("400010");
            IsoMessage original = authorization(card, 7_500);
            assertThat(acquirer.send(original).get(RESPONSE_CODE)).isEqualTo("00");

            IsoMessage response = acquirer.send(reversalOf(original));

            assertThat(response.mti()).isEqualTo(Mti.REVERSAL_RESPONSE);
            assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
            assertThat(simulator("northwind").availableBalance(card)).isEqualTo(INITIAL_BALANCE);
            assertThat(acquirer.send(reversalOf(original)).get(RESPONSE_CODE)).as("second reversal").isEqualTo("25");
        }

        @Test
        void isAcknowledgedAndStoredWhenIssuerIsDown() {
            String card = freshCard("400030");
            IsoMessage original = authorization(card, 4_000);
            assertThat(acquirer.send(original).get(RESPONSE_CODE)).isEqualTo("00");
            setBehavior("cedar", 0, false);

            IsoMessage response = acquirer.send(reversalOf(original));

            assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("1");
            assertThat(simulator("cedar").availableBalance(card)).isEqualTo(INITIAL_BALANCE - 4_000);

            setBehavior("cedar", 0, true);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> simulator("cedar").availableBalance(card) == INITIAL_BALANCE);
        }
    }

    @Nested
    class StandInProcessing {

        @Test
        void approvesWithinLimitsWhenIssuerTimesOutAndChargesOnce() {
            setBehavior("cedar", 300, true); // slower than the 80ms timeout
            String card = freshCard("400030");

            long start = System.nanoTime();
            IsoMessage response = acquirer.send(authorization(card, 5_000));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("1");
            assertThat(response.get(AUTH_ID_RESPONSE)).startsWith("S");
            assertThat(elapsedMs).isLessThan(250);

            // The slow issuer approved too, and the stand-in advice must not post it a second time.
            await().atMost(Duration.ofSeconds(5)).until(() -> storeAndForward.depth("cedar") == 0);
            assertThat(simulator("cedar").availableBalance(card)).isEqualTo(INITIAL_BALANCE - 5_000);
        }

        @Test
        void declinesAboveLimitAndReversesTheIssuersLateApproval() {
            setBehavior("cedar", 300, true);
            String card = freshCard("400030");

            IsoMessage response = acquirer.send(authorization(card, 30_000)); // above the $250 stand-in limit

            assertThat(response.get(RESPONSE_CODE)).isEqualTo("91");
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("1");
            // The issuer approved late and placed a hold; the switch reverses it so the funds are released.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> simulator("cedar").availableBalance(card) == INITIAL_BALANCE);
        }

        @Test
        void circuitBreakerOpensAndAdvicesAreDeliveredAfterRecovery() {
            setBehavior("cedar", 0, false);
            List<String> cards = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String card = freshCard("400030");
                cards.add(card);
                IsoMessage response = acquirer.send(authorization(card, 1_000));
                assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
                assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).isEqualTo("1");
            }
            assertThat(issuer("cedar").circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

            String card = freshCard("400030");
            cards.add(card);
            long start = System.nanoTime();
            IsoMessage response = acquirer.send(authorization(card, 1_000));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(response.get(ADDITIONAL_RESPONSE_DATA)).as("breaker open: no wait").isEqualTo("4");
            assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
            assertThat(elapsedMs).isLessThan(60);

            // Issuer recovers: breaker half-opens, closes, and the queued advices are posted to the accounts.
            setBehavior("cedar", 0, true);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    issuer("cedar").circuitBreaker().getState() == CircuitBreaker.State.CLOSED
                            && storeAndForward.depth("cedar") == 0);
            for (String c : cards) {
                assertThat(simulator("cedar").availableBalance(c)).isEqualTo(INITIAL_BALANCE - 1_000);
            }
        }
    }

    @Test
    void answersEchoTest() {
        IsoMessage echo = new IsoMessage(Mti.NETWORK_MANAGEMENT_REQUEST)
                .set(TRANSMISSION_DATE_TIME, "1006120000")
                .set(STAN, "%06d".formatted(SEQUENCE.getAndIncrement()))
                .set(NETWORK_MANAGEMENT_CODE, "301");

        IsoMessage response = acquirer.send(echo);

        assertThat(response.mti()).isEqualTo(Mti.NETWORK_MANAGEMENT_RESPONSE);
        assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
        assertThat(response.get(NETWORK_MANAGEMENT_CODE)).isEqualTo("301");
    }

    @Test
    void dropsMalformedFrameWithoutClosingTheConnection() {
        acquirer.sendRaw("not an iso message".getBytes(StandardCharsets.US_ASCII));

        IsoMessage response = acquirer.send(authorization(freshCard("400010"), 1_000));

        assertThat(response.get(RESPONSE_CODE)).isEqualTo("00");
    }

    private static IsoMessage authorization(String pan, long amount) {
        int sequence = SEQUENCE.getAndIncrement();
        return new IsoMessage(Mti.AUTHORIZATION_REQUEST)
                .set(PAN, pan)
                .set(PROCESSING_CODE, "000000")
                .set(AMOUNT, "%012d".formatted(amount))
                .set(TRANSMISSION_DATE_TIME, "1006120000")
                .set(STAN, "%06d".formatted(sequence))
                .set(EXPIRATION_DATE, "3012")
                .set(ACQUIRER_ID, "123456")
                .set(RRN, "%012d".formatted(sequence))
                .set(TERMINAL_ID, "TERM0001")
                .set(MERCHANT_ID, "MERCHANT0000001")
                .set(CURRENCY_CODE, "840");
    }

    private static IsoMessage reversalOf(IsoMessage original) {
        return original.copy()
                .setMti(Mti.REVERSAL_REQUEST)
                .set(STAN, "%06d".formatted(SEQUENCE.getAndIncrement()))
                .remove(EXPIRATION_DATE);
    }

    /** A card index not reserved for blocked/stolen cards, never reused across tests. */
    private static String freshCard(String bin) {
        int index;
        do {
            index = NEXT_CARD.getAndIncrement();
        } while (index % 100 == 7 || index % 250 == 13);
        return Pans.testCard(bin, index);
    }

    private Issuer issuer(String id) {
        return issuers.find(id).orElseThrow();
    }

    private SimulatedIssuer simulator(String id) {
        return (SimulatedIssuer) issuer(id).client();
    }

    private void setBehavior(String issuerId, long latencyMs, boolean available) {
        Duration latency = Duration.ofMillis(latencyMs);
        simulator(issuerId).setBehavior(new SimulatedIssuer.Behavior(latency, latency, available));
    }
}
