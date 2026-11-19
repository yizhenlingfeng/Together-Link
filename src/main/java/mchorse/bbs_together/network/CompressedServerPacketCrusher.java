package mchorse.bbs_together.network;

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
        /* Same guard as the client: a chunk over the vanilla 32 KiB inbound limit would make
         * the receiving client error out and drop the connection. Drop it here instead. */
        if (!PacketSizeGuard.fits(buf.readableBytes()))
        {
            System.out.println("[BBS Together] Dropped oversized packet " + identifier
                + " (" + buf.readableBytes() + " bytes > " + PacketSizeGuard.MAX_PAYLOAD + ")");

            return;
        }

        TogetherServerNetworking.send((ServerPlayerEntity) entity, identifier, buf);
    }

    /**
     * {@link #send(PlayerEntity, Identifier, byte[], Consumer)} with a size check up front: a
     * payload the receiving client would refuse to decode is dropped and logged instead of
     * being handed to the encoder, which would otherwise disconnect that client.
     */
    public void sendGuarded(PlayerEntity target, Identifier identifier, byte[] bytes, Consumer<PacketByteBuf> consumer)
    {
        if (bytes != null && !PacketSizeGuard.fits(bytes.length))
        {
            System.out.println("[BBS Together] Skipped oversized payload for " + identifier
                + " (" + bytes.length + " bytes > " + PacketSizeGuard.MAX_PAYLOAD + "); not sent.");

            return;
        }

        this.send(target, identifier, bytes, consumer);
    }

    /** Same as above for a structured payload. */
    public void sendGuarded(PlayerEntity target, Identifier identifier, mchorse.bbs_mod.data.types.BaseType data, Consumer<PacketByteBuf> consumer)
    {
        if (data != null)
        {
            byte[] encoded = mchorse.bbs_mod.data.DataStorageUtils.writeToBytes(data);

            if (!PacketSizeGuard.fits(encoded.length))
            {
                System.out.println("[BBS Together] Skipped oversized payload for " + identifier
                    + " (" + encoded.length + " bytes > " + PacketSizeGuard.MAX_PAYLOAD + "); not sent.");

                return;
            }
        }

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
