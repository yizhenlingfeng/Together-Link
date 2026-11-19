package mchorse.bbs_together.network;

import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.network.PacketCrusher;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Client crusher with {@link PacketCompression} for large payloads.
 *
 * <p>The base {@link PacketCrusher#receive} trusts the chunk header: {@code total} sizes a
 * {@code ByteArrayOutputStream(total * 30_000)} and {@code size} sizes a {@code new byte[size]},
 * both taken from the peer, so one malformed packet could request a multi-gigabyte allocation.
 * The override here validates the header, caps the initial allocation and expires abandoned
 * partial transfers, mirroring {@code SafeServerPacketCrusher} on the server.</p>
 */
@Environment(EnvType.CLIENT)
public class CompressedClientPacketCrusher extends ClientPacketCrusher
{
    private static final int MAX_CHUNKS = 8192;
    private static final int MAX_OPEN_TRANSFERS = 32;
    private static final long MAX_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final long TRANSFER_TIMEOUT_MS = 60_000L;
    private static final int INITIAL_BUFFER_CAP = 1024 * 1024;

    private final Map<Integer, ByteArrayOutputStream> safeChunks = new LinkedHashMap<>();
    private final Map<Integer, Long> safeTouched = new LinkedHashMap<>();
    private final Map<Integer, Long> safeBytes = new LinkedHashMap<>();

    @Override
    protected void sendBuffer(PlayerEntity entity, Identifier identifier, PacketByteBuf buf)
    {
        /* A chunk over the vanilla inbound limit would make the server error out and drop the
         * connection. Drop it here instead; the crusher keeps every chunk well under the limit,
         * so this only catches a path that bypassed its split. */
        if (!PacketSizeGuard.fits(buf.readableBytes()))
        {
            PacketSizeGuard.warn(identifier, buf.readableBytes());

            return;
        }

        TogetherClientNetworking.send(identifier, buf);
    }

    @Override
    public void send(Collection<PlayerEntity> entities, Identifier identifier, byte[] bytes, Consumer<PacketByteBuf> consumer)
    {
        super.send(entities, identifier, PacketCompression.encode(bytes), consumer);
    }

    @Override
    public synchronized void receive(PacketByteBuf buf, IBufferReceiver receiver)
    {
        if (buf.readableBytes() < 16)
        {
            return;
        }

        int id = buf.readInt();
        int index = buf.readInt();
        int total = buf.readInt();
        int size = buf.readInt();

        if (id < 0 || total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
            || size < 0 || size > PacketCrusher.BUFFER_SIZE || buf.readableBytes() < size)
        {
            return;
        }

        long now = System.currentTimeMillis();
        this.expire(now);

        ByteArrayOutputStream chunks = this.safeChunks.get(id);

        if (chunks == null)
        {
            if (this.safeChunks.size() >= MAX_OPEN_TRANSFERS)
            {
                Integer oldest = this.safeTouched.keySet().iterator().next();
                this.discard(oldest);
            }

            int initial = (int) Math.min((long) total * PacketCrusher.BUFFER_SIZE, INITIAL_BUFFER_CAP);
            chunks = new ByteArrayOutputStream(initial);
            this.safeChunks.put(id, chunks);
            this.safeBytes.put(id, 0L);
        }

        long received = this.safeBytes.getOrDefault(id, 0L);

        if (received + size > MAX_TOTAL_BYTES)
        {
            this.discard(id);

            return;
        }

        byte[] bytes = new byte[size];
        buf.readBytes(bytes);
        chunks.writeBytes(bytes);
        this.safeTouched.put(id, now);
        this.safeBytes.put(id, received + size);

        if (index == total - 1)
        {
            byte[] finalBytes = chunks.toByteArray();
            this.discard(id);

            if (finalBytes.length == 1 && finalBytes[0] == 69)
            {
                finalBytes = null;
            }

            receiver.receiveBuffer(PacketCompression.decode(finalBytes), buf);
        }
    }

    private void discard(int id)
    {
        this.safeChunks.remove(id);
        this.safeTouched.remove(id);
        this.safeBytes.remove(id);
    }

    private void expire(long now)
    {
        Iterator<Map.Entry<Integer, Long>> iterator = this.safeTouched.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<Integer, Long> entry = iterator.next();

            if (now - entry.getValue() > TRANSFER_TIMEOUT_MS)
            {
                this.safeChunks.remove(entry.getKey());
                this.safeBytes.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized void reset()
    {
        super.reset();
        this.safeChunks.clear();
        this.safeTouched.clear();
        this.safeBytes.clear();
    }
}
