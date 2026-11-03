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
        TogetherServerNetworking.send((ServerPlayerEntity) entity, identifier, buf);
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
