package com.cardswitch.server.net;

import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.ResponseCode;
import com.cardswitch.server.core.MessageDispatcher;
import com.cardswitch.server.metrics.SwitchMetrics;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Dispatches each decoded message and writes its response when ready. Many requests can be in flight on one
 * connection; responses go out in completion order and the acquirer matches them by STAN, as on a real network link.
 */
@Component
@ChannelHandler.Sharable
class IsoRequestHandler extends SimpleChannelInboundHandler<IsoMessage> {

    private static final Logger log = LoggerFactory.getLogger(IsoRequestHandler.class);

    private final MessageDispatcher dispatcher;
    private final SwitchMetrics metrics;

    IsoRequestHandler(MessageDispatcher dispatcher, SwitchMetrics metrics) {
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        metrics.connectionOpened();
        log.info("Acquirer connected from {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        metrics.connectionClosed();
        log.info("Acquirer disconnected from {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, IsoMessage request) {
        CompletableFuture<IsoMessage> pending;
        try {
            pending = dispatcher.dispatch(request);
        } catch (RuntimeException e) {
            log.error("Failed to process {}", request, e);
            pending = CompletableFuture.completedFuture(dispatcher.errorResponse(request, ResponseCode.SYSTEM_MALFUNCTION));
        }
        pending.whenComplete((response, error) -> {
            IsoMessage reply = response;
            if (error != null) {
                log.error("Failed to process {}", request, error);
                reply = dispatcher.errorResponse(request, ResponseCode.SYSTEM_MALFUNCTION);
            }
            if (reply != null && ctx.channel().isActive()) {
                ctx.writeAndFlush(reply).addListener(write -> {
                    if (!write.isSuccess()) {
                        log.warn("Failed to send response to {}: {}", ctx.channel().remoteAddress(), write.cause().toString());
                    }
                });
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Closing connection from {}: {}", ctx.channel().remoteAddress(), cause.toString());
        ctx.close();
    }
}
