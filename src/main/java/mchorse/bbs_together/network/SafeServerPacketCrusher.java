package mchorse.bbs_together.network;

import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.network.PacketCrusher;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import net.minecraft.network.PacketByteBuf;

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BBS' {@code PacketCrusher.receive} trusts the chunk header: {@code total} drives a
 * {@code ByteArrayOutputStream(total * 30_000)} allocation and {@code size} drives a
 * {@code new byte[size]}, both from the peer. A single tiny packet can therefore request a
 * multi-gigabyte allocation. This subclass keeps the same wire format but validates the
 * header, caps the initial allocation and expires abandoned partial transfers.
 */
public class SafeServerPacketCrusher extends ServerPacketCrusher {
    public static final int MAX_CHUNKS = 8192;
    public static final int MAX_OPEN_TRANSFERS = 64;
    public static final long TRANSFER_TIMEOUT_MS = 60_000L;
    private static final int INITIAL_BUFFER_CAP = 4 * 1024 * 1024;

    private final Map<Integer, ByteArrayOutputStream> safeChunks = new LinkedHashMap<>();
    private final Map<Integer, Long> safeTouched = new LinkedHashMap<>();

    @Override
    public synchronized void receive(PacketByteBuf buf, IBufferReceiver receiver) {
        if (buf.readableBytes() < 16) {
            return;
        }

        int id = buf.readInt();
        int index = buf.readInt();
        int total = buf.readInt();
        int size = buf.readInt();

        if (id < 0 || total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
            || size < 0 || size > PacketCrusher.BUFFER_SIZE || buf.readableBytes() < size) {
            return;
        }

        long now = System.currentTimeMillis();
        this.expire(now);

        ByteArrayOutputStream chunks = this.safeChunks.get(id);

        if (chunks == null) {
            if (this.safeChunks.size() >= MAX_OPEN_TRANSFERS) {
                Integer oldest = this.safeTouched.keySet().iterator().next();
                this.safeChunks.remove(oldest);
                this.safeTouched.remove(oldest);
            }

            int initial = (int) Math.min((long) total * PacketCrusher.BUFFER_SIZE, INITIAL_BUFFER_CAP);
            chunks = new ByteArrayOutputStream(initial);
            this.safeChunks.put(id, chunks);
        }

        byte[] bytes = new byte[size];
        buf.readBytes(bytes);
        chunks.writeBytes(bytes);
        this.safeTouched.put(id, now);

        if (index == total - 1) {
            byte[] finalBytes = chunks.toByteArray();
            this.safeChunks.remove(id);
            this.safeTouched.remove(id);

            if (finalBytes.length == 1 && finalBytes[0] == 69) {
                finalBytes = null;
            }

            receiver.receiveBuffer(finalBytes, buf);
        }
    }

    private void expire(long now) {
        Iterator<Map.Entry<Integer, Long>> iterator = this.safeTouched.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Integer, Long> entry = iterator.next();

            if (now - entry.getValue() > TRANSFER_TIMEOUT_MS) {
                this.safeChunks.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized void reset() {
        super.reset();
        this.safeChunks.clear();
        this.safeTouched.clear();
    }
}
