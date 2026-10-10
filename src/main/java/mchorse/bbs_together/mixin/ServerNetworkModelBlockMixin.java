package mchorse.bbs_together.mixin;

import mchorse.bbs_together.behaviors.ModelBlockTracker;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The form packet's chunk header carries the target block position at its tail
 * (only on the last chunk). Reading it off a duplicate of the buffer here lets
 * the recorder know which player is about to update which model block — the
 * block entity itself never sees the player.
 */
@Mixin(mchorse.bbs_mod.network.ServerNetwork.class)
public class ServerNetworkModelBlockMixin
{
    @Inject(method = "handleModelBlockFormPacket", at = @At("HEAD"))
    private static void together$armModelBlockTracker(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        try
        {
            /* Chunk header: [id][index][total][size][bytes] (+ metadata on the last chunk) */
            PacketByteBuf copy = new PacketByteBuf(buf.duplicate());

            copy.readInt(); // chunk id

            int index = copy.readInt();
            int total = copy.readInt();
            int size = copy.readInt();

            copy.skipBytes(size);

            /* Payload metadata (the position) rides only on the last chunk */
            if (index == total - 1 && copy.isReadable(1))
            {
                BlockPos pos = copy.readBlockPos();

                ModelBlockTracker.arm(pos, player.getUuid());
            }
        }
        catch (Exception ignored)
        {}
    }
}
