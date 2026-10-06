package com.cardswitch.server.net;

import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.server.config.SwitchProperties;
import com.cardswitch.server.metrics.SwitchMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.flush.FlushConsolidationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * The ISO 8583 TCP listener. Each frame is a 2-byte big-endian length header followed by the message, the most
 * common framing on card network links.
 * <p>
 * Started last and stopped first by Spring, so traffic is only accepted once every other component is ready.
 */
@Component
public class IsoServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IsoServer.class);
    private static final int LENGTH_HEADER_BYTES = 2;

    private final SwitchProperties.Server config;
    private final IsoCodec codec;
    private final IsoRequestHandler handler;
    private final SwitchMetrics metrics;

    private EventLoopGroup acceptorGroup;
    private EventLoopGroup ioGroup;
    private volatile Channel serverChannel;

    IsoServer(SwitchProperties properties, IsoCodec codec, IsoRequestHandler handler, SwitchMetrics metrics) {
        this.config = properties.server();
        this.codec = codec;
        this.handler = handler;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        acceptorGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        ioGroup = new MultiThreadIoEventLoopGroup(config.ioThreads(), NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(acceptorGroup, ioGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                // batches flushes while a read burst is in progress: fewer syscalls under load
                                .addLast("flushConsolidation", new FlushConsolidationHandler(256, true))
                                .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                                        config.maxFrameLength(), 0, LENGTH_HEADER_BYTES, 0, LENGTH_HEADER_BYTES))
                                .addLast("frameEncoder", new LengthFieldPrepender(LENGTH_HEADER_BYTES))
                                .addLast("isoCodec", new IsoMessageCodec(codec, metrics))
                                .addLast("dispatcher", handler);
                    }
                });
        try {
            serverChannel = bootstrap.bind(config.host(), config.port()).syncUninterruptibly().channel();
        } catch (RuntimeException e) {
            shutdownGroups();
            throw e;
        }
        log.info("ISO 8583 server listening on {}:{}", config.host(), port());
    }

    @Override
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        shutdownGroups();
        log.info("ISO 8583 server stopped");
    }

    @Override
    public boolean isRunning() {
        Channel channel = serverChannel;
        return channel != null && channel.isOpen();
    }

    /** The bound port (useful when configured as 0 for an ephemeral port). */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    private void shutdownGroups() {
        if (acceptorGroup != null) {
            acceptorGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (ioGroup != null) {
            ioGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
