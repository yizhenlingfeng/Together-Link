package mchorse.bbs_together.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Thin 1.20 networking facade. The 1.21.11 build replaces this class with a
 * payload-based overlay, keeping the old handler lambda shape so the business
 * handlers can stay on {@link PacketByteBuf}.
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
        /* 1.20's networking API registers channels on first use. */
    }

    public static void register(Identifier id, Receiver receiver)
    {
        ClientPlayNetworking.registerGlobalReceiver(id, receiver::receive);
    }

    public static void send(Identifier id, PacketByteBuf buf)
    {
        ClientPlayNetworking.send(id, buf);
    }

    public static boolean canSend(Identifier id)
    {
        return ClientPlayNetworking.canSend(id);
    }
}
