package mchorse.bbs_together.network;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Thin 1.20 networking facade. The 1.21.11 build replaces this class with a
 * payload-based overlay, keeping the old handler lambda shape so the business
 * handlers can stay on {@link PacketByteBuf}.
 */
public final class TogetherServerNetworking
{
    private TogetherServerNetworking()
    {}

    @FunctionalInterface
    public interface Receiver
    {
        void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender);
    }

    public static void preRegister()
    {
        /* 1.20's networking API registers channels on first use. */
    }

    public static void register(Identifier id, Receiver receiver)
    {
        ServerPlayNetworking.registerGlobalReceiver(id, receiver::receive);
    }

    public static void send(ServerPlayerEntity player, Identifier id, PacketByteBuf buf)
    {
        ServerPlayNetworking.send(player, id, buf);
    }

    public static boolean canSend(ServerPlayerEntity player, Identifier id)
    {
        return ServerPlayNetworking.canSend(player, id);
    }
}
