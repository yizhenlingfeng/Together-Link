package mchorse.bbs_together.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * 1.21.11 payload networking facade. The receiver intentionally keeps the old
 * 1.20 handler lambda shape, so the 100+ business handlers remain unchanged.
 */
public final class TogetherClientNetworking
{
    private TogetherClientNetworking()
    {}

    @FunctionalInterface
    public interface Receiver
    {
        void receive(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender);
    }

    public static void preRegister()
    {
        TogetherPayloads.registerAll(TogetherServerNetwork.class);
    }

    public static void register(Identifier id, Receiver receiver)
    {
        TogetherPayloads.ensureRegistered(id);

        ClientPlayNetworking.registerGlobalReceiver(TogetherPayloads.idFor(id), (payload, context) -> {
            MinecraftClient client = context.client();

            receiver.receive(client, client.getNetworkHandler(), payload.asPacketByteBuf(), context.responseSender());
        });
    }

    public static void send(Identifier id, PacketByteBuf buf)
    {
        TogetherPayloads.ensureRegistered(id);

        ClientPlayNetworking.send(TogetherPayload.from(buf, TogetherPayloads.idFor(id)));
    }

    public static boolean canSend(Identifier id)
    {
        return ClientPlayNetworking.canSend(id);
    }
}
