package com.cardswitch.server.auth;

import com.cardswitch.server.config.SwitchProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static com.cardswitch.server.auth.VelocityTracker.Result.AMOUNT_EXCEEDED;
import static com.cardswitch.server.auth.VelocityTracker.Result.COUNT_EXCEEDED;
import static com.cardswitch.server.auth.VelocityTracker.Result.OK;
import static org.assertj.core.api.Assertions.assertThat;

class VelocityTrackerTest {

    private static final String CARD = "4000100000000006";
    private static final String OTHER_CARD = "4000100000000014";

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final VelocityTracker tracker = new VelocityTracker(
            new SwitchProperties.Velocity(Duration.ofSeconds(60), 3, 10_000), now::get);

    @Test
    void limitsAttemptsPerWindow() {
        assertThat(tracker.check(CARD, 100)).isEqualTo(OK);
        assertThat(tracker.check(CARD, 100)).isEqualTo(OK);
        assertThat(tracker.check(CARD, 100)).isEqualTo(OK);
        assertThat(tracker.check(CARD, 100)).isEqualTo(COUNT_EXCEEDED);
        assertThat(tracker.check(OTHER_CARD, 100)).as("cards are tracked independently").isEqualTo(OK);
    }

    @Test
    void limitsCumulativeAmountPerWindow() {
        assertThat(tracker.check(CARD, 6_000)).isEqualTo(OK);
        assertThat(tracker.check(CARD, 5_000)).isEqualTo(AMOUNT_EXCEEDED);
        assertThat(tracker.check(CARD, 4_000)).isEqualTo(OK);
    }

    @Test
    void slidesTheWindow() {
        tracker.check(CARD, 100);
        advance(Duration.ofSeconds(30));
        tracker.check(CARD, 100);
        tracker.check(CARD, 100);
        assertThat(tracker.check(CARD, 100)).isEqualTo(COUNT_EXCEEDED);

        advance(Duration.ofSeconds(31)); // the first attempt is now older than 60s
        assertThat(tracker.check(CARD, 100)).isEqualTo(OK);
        assertThat(tracker.check(CARD, 100)).isEqualTo(COUNT_EXCEEDED);
    }

    @Test
    void rejectedAttemptsDoNotConsumeTheLimit() {
        tracker.check(CARD, 9_000);
        assertThat(tracker.check(CARD, 2_000)).isEqualTo(AMOUNT_EXCEEDED);
        assertThat(tracker.check(CARD, 1_000)).isEqualTo(OK);
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }
}
