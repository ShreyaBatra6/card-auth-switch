package com.cardswitch.server.config;

import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.iso8583.IsoSpec;
import com.cardswitch.server.issuer.PreciseDelayScheduler;
import com.cardswitch.server.issuer.SimulatedIssuer;
import com.cardswitch.server.routing.Issuer;
import com.cardswitch.server.routing.IssuerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(SwitchProperties.class)
public class SwitchConfiguration {

    public static final String SWITCH_SCHEDULER = "switchScheduler";
    public static final String SIMULATOR_SCHEDULER = "issuerSimulatorScheduler";

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IsoCodec isoCodec() {
        return new IsoCodec(IsoSpec.iso87Ascii());
    }

    /**
     * Fires issuer timeouts and drives store-and-forward delivery. On Windows, timed waits round up to the 15.6 ms
     * timer tick, so a 100 ms issuer timeout fires after 100-116 ms there; Linux timers are far finer.
     */
    @Bean(name = SWITCH_SCHEDULER, destroyMethod = "shutdownNow")
    public ScheduledExecutorService switchScheduler() {
        return scheduler("switch-timer-", 2);
    }

    /**
     * Delays simulated issuer responses precisely (see {@link PreciseDelayScheduler} for why OS timers won't do).
     * Separate from the switch's own timers, as a remote host would be.
     */
    @Bean(name = SIMULATOR_SCHEDULER, destroyMethod = "close")
    public PreciseDelayScheduler issuerSimulatorScheduler() {
        return new PreciseDelayScheduler("issuer-sim-timer");
    }

    @Bean
    public IssuerRegistry issuerRegistry(SwitchProperties properties,
                                         @Qualifier(SIMULATOR_SCHEDULER) PreciseDelayScheduler simulatorScheduler) {
        CircuitBreakerConfig breakerConfig = circuitBreakerConfig(properties.circuitBreaker());
        List<Issuer> issuers = new ArrayList<>();
        for (SwitchProperties.IssuerProperties p : properties.issuers()) {
            SimulatedIssuer host = new SimulatedIssuer(p.id(), p.bins().getFirst(), p.simulator(), simulatorScheduler);
            issuers.add(new Issuer(p.id(), p.name(), List.copyOf(p.bins()), host,
                    CircuitBreaker.of(p.id(), breakerConfig), p.standIn()));
        }
        return new IssuerRegistry(issuers);
    }

    private static CircuitBreakerConfig circuitBreakerConfig(SwitchProperties.Breaker breaker) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .failureRateThreshold(breaker.failureRateThreshold())
                .waitDurationInOpenState(breaker.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    private static ScheduledExecutorService scheduler(String threadPrefix, int threads) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads,
                Thread.ofPlatform().name(threadPrefix, 0).daemon(true).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
