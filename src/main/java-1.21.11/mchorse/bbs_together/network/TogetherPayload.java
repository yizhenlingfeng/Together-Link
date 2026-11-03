package mchorse.bbs_together.network;

import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 1.21.11 payload wrapper. It carries the same {@link PacketByteBuf} bytes as
 * the 1.20 networking API so the business handlers do not need to change.
 */
public record TogetherPayload(byte[] data, CustomPayload.Id<TogetherPayload> id) implements CustomPayload
{
    public static TogetherPayload from(PacketByteBuf buf, CustomPayload.Id<TogetherPayload> id)
    {
        byte[] bytes = new byte[buf.readableBytes()];

        buf.readBytes(bytes);

        return new TogetherPayload(bytes, id);
    }

    public PacketByteBuf asPacketByteBuf()
    {
        PacketByteBuf out = PacketByteBufs.create();

        out.writeBytes(this.data);

        return out;
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId()
    {
        return this.id;
    }

    public static PacketCodec<RegistryByteBuf, TogetherPayload> codecFor(CustomPayload.Id<TogetherPayload> id)
    {
        return new PacketCodec<>()
        {
            @Override
            public TogetherPayload decode(RegistryByteBuf byteBuf)
            {
                byte[] bytes = new byte[byteBuf.readableBytes()];

                byteBuf.readBytes(bytes);

                return new TogetherPayload(bytes, id);
            }

            @Override
            public void encode(RegistryByteBuf byteBuf, TogetherPayload payload)
            {
                byteBuf.writeBytes(payload.data);
            }
        };
    }
}
