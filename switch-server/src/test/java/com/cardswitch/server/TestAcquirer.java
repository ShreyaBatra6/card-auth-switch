package com.cardswitch.server;

import com.cardswitch.iso8583.IsoCodec;
import com.cardswitch.iso8583.IsoMessage;
import com.cardswitch.iso8583.IsoSpec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;

/**
 * Minimal blocking acquirer for tests: one request at a time over a 2-byte length-prefixed TCP connection.
 */
final class TestAcquirer implements AutoCloseable {

    private final IsoCodec codec = new IsoCodec(IsoSpec.iso87Ascii());
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    TestAcquirer(int port) {
        try {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5_000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    IsoMessage send(IsoMessage request) {
        sendRaw(codec.pack(request));
        return receive();
    }

    void sendRaw(byte[] payload) {
        try {
            out.writeShort(payload.length);
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    IsoMessage receive() {
        try {
            byte[] frame = new byte[in.readUnsignedShort()];
            in.readFully(frame);
            return codec.unpack(frame);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
