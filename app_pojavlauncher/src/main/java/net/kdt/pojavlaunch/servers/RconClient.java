package net.kdt.pojavlaunch.servers;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Source RCON client, the command channel Minecraft dedicated servers expose on a
 * TCP port. The in-process server does not read the host process' stdin, so this is the
 * reliable way to feed console commands (including "stop") to a running server.
 */
public class RconClient implements Closeable {
    private static final int TYPE_AUTH = 3;
    private static final int TYPE_AUTH_RESPONSE = 2;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_RESPONSE = 0;

    private final Socket mSocket;
    private final InputStream mInput;
    private final OutputStream mOutput;
    private int mRequestId = 1;

    public RconClient(String host, int port, String password) throws IOException {
        mSocket = new Socket();
        mSocket.connect(new InetSocketAddress(host, port), 5000);
        mSocket.setSoTimeout(10000);
        mInput = mSocket.getInputStream();
        mOutput = mSocket.getOutputStream();
        Packet authReply = transact(TYPE_AUTH, password);
        if (authReply.id == -1) throw new IOException("RCON authentication rejected");
    }

    /** @return the server's textual response to the command */
    public synchronized String command(String command) throws IOException {
        Packet reply = transact(TYPE_COMMAND, command);
        if (reply.type != TYPE_RESPONSE) throw new IOException("Unexpected RCON reply type " + reply.type);
        return reply.payload;
    }

    private Packet transact(int type, String payload) throws IOException {
        int id = mRequestId++;
        writePacket(id, type, payload);
        return readPacket();
    }

    private void writePacket(int id, int type, String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + payloadBytes.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(buffer.capacity() - 4); // length excludes itself
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(payloadBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        mOutput.write(buffer.array());
        mOutput.flush();
    }

    private Packet readPacket() throws IOException {
        byte[] header = readExactly(12);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getInt();
        if (length < 10 || length > 65536) throw new IOException("Bad RCON packet length " + length);
        int id = buffer.getInt();
        int type = buffer.getInt();
        byte[] body = readExactly(length - 10);
        // strip the two trailing null terminators
        int end = body.length;
        while (end > 0 && body[end - 1] == 0) end--;
        return new Packet(id, type, new String(body, 0, end, StandardCharsets.UTF_8));
    }

    private byte[] readExactly(int count) throws IOException {
        byte[] data = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = mInput.read(data, offset, count - offset);
            if (read == -1) throw new IOException("RCON connection closed");
            offset += read;
        }
        return data;
    }

    @Override
    public void close() {
        try {
            mSocket.close();
        } catch (IOException ignored) {}
    }

    private static class Packet {
        final int id;
        final int type;
        final String payload;
        Packet(int id, int type, String payload) {
            this.id = id;
            this.type = type;
            this.payload = payload;
        }
    }
}
