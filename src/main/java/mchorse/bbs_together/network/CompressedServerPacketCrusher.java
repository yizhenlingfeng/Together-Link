package mchorse.bbs_together.network;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.network.IBufferReceiver;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.function.Consumer;

/** Server crusher with {@link PacketCompression} for large payloads. */
public class CompressedServerPacketCrusher extends SafeServerPacketCrusher
{
    @Override
    protected void sendBuffer(PlayerEntity entity, Identifier identifier, PacketByteBuf buf)
    {
        /* A chunk over the vanilla 32 KiB inbound limit would make the receiving client error
         * out and drop the connection. Drop it here instead; the crusher keeps every chunk
         * well under the limit, so this only catches a path that bypassed its split. */
        if (!PacketSizeGuard.fits(buf.readableBytes()))
        {
            PacketSizeGuard.warn(identifier, buf.readableBytes());

            return;
        }

        TogetherServerNetworking.send((ServerPlayerEntity) entity, identifier, buf);
    }

    /**
     * Hands the whole payload to the crusher, which splits it into chunks of at most 30,000 bytes
     * plus a 16-byte header. The per-chunk check in {@link #sendBuffer} covers the actual encoder
     * limit, so there is no whole-payload check here — doing it before the split rejected every
     * large transfer (asset slices, films, manifests) while the split already made it safe.
     */
    public void sendGuarded(PlayerEntity target, Identifier identifier, byte[] bytes, Consumer<PacketByteBuf> consumer)
    {
        this.send(target, identifier, bytes, consumer);
    }

    /** Same as above for a structured payload; it is serialized once, by the crusher. */
    public void sendGuarded(PlayerEntity target, Identifier identifier, BaseType data, Consumer<PacketByteBuf> consumer)
    {
        this.send(target, identifier, data, consumer);
    }

    @Override
    public void send(Collection<PlayerEntity> entities, Identifier identifier, byte[] bytes, Consumer<PacketByteBuf> consumer)
    {
        super.send(entities, identifier, PacketCompression.encode(bytes), consumer);
    }

    @Override
    public void receive(PacketByteBuf buf, IBufferReceiver receiver)
    {
        super.receive(buf, (bytes, packetByteBuf) -> receiver.receiveBuffer(PacketCompression.decode(bytes), packetByteBuf));
    }
}
