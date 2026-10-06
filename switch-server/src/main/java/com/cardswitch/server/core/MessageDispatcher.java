package com.cardswitch.server.core;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.Mti;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.server.auth.AuthorizationService;
import com.cardswitch.server.auth.ReversalService;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Routes an incoming message to its handler by MTI.
 */
@Component
public class MessageDispatcher {

    private final AuthorizationService authorizations;
    private final ReversalService reversals;
    private final NetworkManagementService networkManagement;

    public MessageDispatcher(AuthorizationService authorizations, ReversalService reversals,
                             NetworkManagementService networkManagement) {
        this.authorizations = authorizations;
        this.reversals = reversals;
        this.networkManagement = networkManagement;
    }

    /**
     * @return the response to send back, or a future of {@code null} if the message does not get one
     */
    public CompletableFuture<IsoMessage> dispatch(IsoMessage request) {
        return switch (request.mti()) {
            case Mti.AUTHORIZATION_REQUEST -> authorizations.authorize(request);
            case Mti.REVERSAL_REQUEST -> reversals.reverse(request);
            case Mti.NETWORK_MANAGEMENT_REQUEST -> CompletableFuture.completedFuture(networkManagement.handle(request));
            default -> CompletableFuture.completedFuture(errorResponse(request, ResponseCode.INVALID_TRANSACTION));
        };
    }

    /**
     * @return a response carrying {@code code}, or {@code null} if the message type does not expect a response
     */
    public IsoMessage errorResponse(IsoMessage request, ResponseCode code) {
        return Mti.expectsResponse(request.mti()) ? Responses.replyTo(request, code) : null;
    }
}
