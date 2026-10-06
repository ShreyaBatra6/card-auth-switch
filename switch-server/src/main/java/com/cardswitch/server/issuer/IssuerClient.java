package com.cardswitch.server.issuer;

import com.cardswitch.iso8583.IsoMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Connection to an issuer's authorization host. The interface is message based (ISO 8583 in, ISO 8583 out), so the
 * in-process simulator can be replaced by a TCP connection to a real host without touching routing logic.
 */
public interface IssuerClient {

    /**
     * Sends a request (0100, 0400) or advice (0120). The returned future completes with the issuer's response, or
     * never completes if the host is down. Callers are responsible for timeouts.
     */
    CompletableFuture<IsoMessage> send(IsoMessage message);
}
