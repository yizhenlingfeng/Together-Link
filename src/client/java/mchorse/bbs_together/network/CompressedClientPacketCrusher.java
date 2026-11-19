package mchorse.bbs_together.network;

import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.network.IBufferReceiver;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.function.Consumer;

/** Client crusher with {@link PacketCompression} for large payloads. */
@Environment(EnvType.CLIENT)
public class CompressedClientPacketCrusher extends ClientPacketCrusher
{
    @Override
    protected void sendBuffer(PlayerEntity entity, Identifier identifier, PacketByteBuf buf)
    {
        /* A chunk over the vanilla 32 KiB inbound limit would make the server error out and
         * drop the connection. Drop it here instead; the crusher keeps every chunk well under
         * the limit, so this only catches a path that bypassed its split. */
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
    public void receive(PacketByteBuf buf, IBufferReceiver receiver)
    {
        super.receive(buf, (bytes, packetByteBuf) -> receiver.receiveBuffer(PacketCompression.decode(bytes), packetByteBuf));
    }
}
