package com.cardswitch.server.core;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.cardswitch.iso8583.Field.NETWORK_MANAGEMENT_CODE;

/**
 * 0800 network management: sign-on, sign-off and echo test (the heartbeat acquirers send on idle connections).
 */
@Service
public class NetworkManagementService {

    private static final Logger log = LoggerFactory.getLogger(NetworkManagementService.class);

    private static final Map<String, String> SUPPORTED = Map.of(
            "001", "sign-on",
            "002", "sign-off",
            "301", "echo test");

    public IsoMessage handle(IsoMessage request) {
        String code = request.get(NETWORK_MANAGEMENT_CODE);
        if (code == null) {
            return Responses.replyTo(request, ResponseCode.FORMAT_ERROR);
        }
        String action = SUPPORTED.get(code);
        if (action == null) {
            return Responses.replyTo(request, ResponseCode.INVALID_TRANSACTION);
        }
        if (!"301".equals(code)) {
            log.info("Network management {} received", action);
        }
        return Responses.replyTo(request, ResponseCode.APPROVED);
    }
}
