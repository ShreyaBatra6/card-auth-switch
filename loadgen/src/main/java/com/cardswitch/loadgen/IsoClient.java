package com.cardswitch.loadgen;

import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.IsoSpec;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static com.cardswitch.iso8583.Field.STAN;
import static com.cardswitch.iso8583.Field.TERMINAL_ID;

/**
 * Multiplexing ISO 8583 client: a pool of TCP connections, each with many requests in flight. Responses can come
 * back in any order and are matched to requests by STAN, which is unique per connection (terminal).
 */
final class IsoClient implements AutoCloseable {

    private record Pending(CompletableFuture<IsoMessage> future, long sentNanos) {
    }

    private static final class Connection {

        final String terminalId;
        final Map<String, Pending> inFlight = new ConcurrentHashMap<>();
        final AtomicInteger stan = new AtomicInteger();
        Channel channel;

        Connection(String terminalId) {
            this.terminalId = terminalId;
        }

        String nextStan() {
            return "%06d".formatted(stan.updateAndGet(s -> s >= 999_999 ? 1 : s + 1));
        }
    }

    private final IsoCodec codec = new IsoCodec(IsoSpec.iso87Ascii());
    private final EventLoopGroup group;
    private final Connection[] connections;
    private final AtomicInteger next = new AtomicInteger();
    private final LongAdder unmatched = new LongAdder();

    IsoClient(String host, int port, int connectionCount, int ioThreads) {
        group = new MultiThreadIoEventLoopGroup(ioThreads, NioIoHandler.newFactory());
        connections = new Connection[connectionCount];
        try {
            for (int i = 0; i < connectionCount; i++) {
                Connection connection = new Connection("LOAD%04d".formatted(i + 1));
                connection.channel = new Bootstrap()
                        .group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel channel) {
                                channel.pipeline()
                                        .addLast(new LengthFieldBasedFrameDecoder(65_535, 0, 2, 0, 2))
                                        .addLast(new LengthFieldPrepender(2))
                                        .addLast(new ResponseHandler(connection));
                            }
                        })
                        .connect(host, port).syncUninterruptibly().channel();
                connections[i] = connection;
            }
        } catch (RuntimeException e) {
            group.shutdownGracefully();
            throw e;
        }
    }

    /**
     * Assigns a STAN and terminal ID, sends the request and returns its response.
     */
    CompletableFuture<IsoMessage> send(IsoMessage request) {
        Connection connection = connections[Math.floorMod(next.getAndIncrement(), connections.length)];
        String stan = connection.nextStan();
        request.set(STAN, stan).set(TERMINAL_ID, connection.terminalId);
        byte[] payload = codec.pack(request);

        CompletableFuture<IsoMessage> future = new CompletableFuture<>();
        connection.inFlight.put(stan, new Pending(future, System.nanoTime()));
        connection.channel.writeAndFlush(Unpooled.wrappedBuffer(payload)).addListener(write -> {
            if (!write.isSuccess() && connection.inFlight.remove(stan) != null) {
                future.completeExceptionally(write.cause());
            }
        });
        return future;
    }

    int inFlight() {
        int total = 0;
        for (Connection connection : connections) {
            total += connection.inFlight.size();
        }
        return total;
    }

    /** Fails requests that have waited longer than {@code timeoutNanos}; returns how many. */
    int expire(long timeoutNanos) {
        long now = System.nanoTime();
        int expired = 0;
        for (Connection connection : connections) {
            Iterator<Map.Entry<String, Pending>> it = connection.inFlight.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Pending> entry = it.next();
                if (now - entry.getValue().sentNanos() > timeoutNanos && connection.inFlight.remove(entry.getKey(), entry.getValue())) {
                    entry.getValue().future().completeExceptionally(new TimeoutException("No response"));
                    expired++;
                }
            }
        }
        return expired;
    }

    long unmatchedResponses() {
        return unmatched.sum();
    }

    IsoSpec spec() {
        return codec.spec();
    }

    @Override
    public void close() {
        for (Connection connection : connections) {
            if (connection != null) {
                connection.channel.close();
            }
        }
        group.shutdownGracefully().syncUninterruptibly();
    }

    private final class ResponseHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private final Connection connection;

        ResponseHandler(Connection connection) {
            this.connection = connection;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            IsoMessage response = codec.unpack(ByteBufUtil.getBytes(frame));
            Pending pending = response.has(STAN) ? connection.inFlight.remove(response.get(STAN)) : null;
            if (pending == null) {
                unmatched.increment();
            } else {
                pending.future().complete(response);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            connection.inFlight.values().forEach(p -> p.future().completeExceptionally(new IllegalStateException("Connection closed")));
            connection.inFlight.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Connection " + connection.terminalId + " error: " + cause);
            ctx.close();
        }
    }
}
