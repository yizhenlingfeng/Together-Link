package mchorse.bbs_together.network;

import mchorse.bbs_together.BBSTogetherFix28;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 1.21.11 payload networking facade. The receiver intentionally keeps the old
 * 1.20 handler lambda shape, so the 100+ business handlers remain unchanged.
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
        TogetherPayloads.registerAll(TogetherServerNetwork.class);
        TogetherPayloads.registerAll(BBSTogetherFix28.class);
    }

    public static void register(Identifier id, Receiver receiver)
    {
        TogetherPayloads.ensureRegistered(id);

        ServerPlayNetworking.registerGlobalReceiver(TogetherPayloads.idFor(id), (payload, context) -> {
            ServerPlayerEntity player = context.player();

            receiver.receive(context.server(), player, player.networkHandler, payload.asPacketByteBuf(), context.responseSender());
        });
    }

    public static void send(ServerPlayerEntity player, Identifier id, PacketByteBuf buf)
    {
        TogetherPayloads.ensureRegistered(id);

        ServerPlayNetworking.send(player, TogetherPayload.from(buf, TogetherPayloads.idFor(id)));
    }

    public static boolean canSend(ServerPlayerEntity player, Identifier id)
    {
        return ServerPlayNetworking.canSend(player, id);
    }
}
