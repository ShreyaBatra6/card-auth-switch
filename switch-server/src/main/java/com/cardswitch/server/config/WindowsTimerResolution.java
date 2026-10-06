package com.cardswitch.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * On Windows the default timer granularity is 15.6 ms, so a task scheduled 2 ms ahead actually runs ~15.6 ms later.
 * That would distort every simulated issuer latency and the issuer timeout itself (measured: p50 of a 2 ms
 * {@code schedule()} was 15.6 ms; with this workaround 2.7 ms).
 * <p>
 * HotSpot on Windows requests 1 ms timer resolution for the duration of any {@code Thread.sleep} whose length is not
 * a multiple of 10 ms, so a daemon thread that sleeps "forever" keeps 1 ms resolution for the whole process.
 * Not needed on Linux, where the switch would run in production.
 */
@Component
class WindowsTimerResolution {

    private static final Logger log = LoggerFactory.getLogger(WindowsTimerResolution.class);

    WindowsTimerResolution() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        Thread.ofPlatform().name("windows-timer-resolution").daemon(true).start(() -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        log.info("Requested 1 ms timer resolution (Windows)");
    }
}
