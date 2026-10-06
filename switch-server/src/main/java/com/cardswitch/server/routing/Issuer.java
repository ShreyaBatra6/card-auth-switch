package com.cardswitch.server.routing;

import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.issuer.IssuerClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.List;

/**
 * An issuer the switch can route to: the BINs it owns, the connection to its host, the health of that connection
 * and the stand-in limits it has agreed to.
 */
public record Issuer(String id, String name, List<String> bins, IssuerClient client, CircuitBreaker circuitBreaker,
                     SwitchProperties.StandIn standIn) {
}
