package com.cardswitch.server.net;

import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.iso8583.IsoFormatException;
import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.server.metrics.SwitchMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Converts between length-delimited frames and {@link IsoMessage}s.
 * <p>
 * A frame that does not parse is dropped and counted rather than closing the connection: one bad message from a
 * terminal should not take down every other transaction multiplexed on the same link. The raw bytes are never
 * logged because they may contain a card number.
 */
final class IsoMessageCodec extends MessageToMessageCodec<ByteBuf, IsoMessage> {

    private static final Logger log = LoggerFactory.getLogger(IsoMessageCodec.class);

    private final IsoCodec codec;
    private final SwitchMetrics metrics;

    IsoMessageCodec(IsoCodec codec, SwitchMetrics metrics) {
        this.codec = codec;
        this.metrics = metrics;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        try {
            out.add(codec.unpack(ByteBufUtil.getBytes(frame)));
        } catch (IsoFormatException e) {
            metrics.malformedMessage();
            log.warn("Dropping malformed ISO 8583 message ({} bytes) from {}: {}",
                    frame.readableBytes(), ctx.channel().remoteAddress(), e.getMessage());
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, IsoMessage message, List<Object> out) {
        out.add(Unpooled.wrappedBuffer(codec.pack(message)));
    }
}
