package com.cardswitch.server.routing;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseSource;

/**
 * The decision for a routed message: the issuer's response, or the switch's fallback, and which one it was.
 */
public record IssuerOutcome(IsoMessage response, ResponseSource source) {
}
