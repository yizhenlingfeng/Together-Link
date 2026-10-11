package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_together.bbsop.BbsOpManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side hard stop for level-0 (read-only) players on BBS's own write packets.
 *
 * <p>These handlers already gate on {@code PermissionUtils.arePanelsAllowed} (global gamerule /
 * OP), but a level-0 player must be refused even when the gamerule opens editing to everyone.
 * We cancel at HEAD: model-block form/transform writes and the morph (player form) packet.
 *
 * <p>{@code handleManagerDataPacket} is intentionally <b>not</b> hooked: it carries both LOAD
 * (reading a film, which a read-only player must be allowed to do — "download" a film to view)
 * and SAVE/RENAME/DELETE, and the crusher-framed buffer means we can't cheaply branch on the
 * operation at HEAD. Film writes are already refused in bbs-together's own
 * handleFilmSaveSync / handleFilmDataSync / handleFilmDeleted / handleFilmRenamed.</p>
 */
@Mixin(value = ServerNetwork.class, remap = false)
public class ServerNetworkBbsOpMixin
{
    @Inject(method = "handleModelBlockFormPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockModelBlockForm(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (BbsOpManager.isReadOnly(player))
        {
            ci.cancel();
        }
    }

    @Inject(method = "handleModelBlockTransformsPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockModelBlockTransforms(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (BbsOpManager.isReadOnly(player))
        {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerFormPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockPlayerForm(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (BbsOpManager.isReadOnly(player))
        {
            ci.cancel();
        }
    }
}
