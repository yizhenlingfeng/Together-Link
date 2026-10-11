package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_together.bbsop.BbsOpManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Levels 1/2 are an explicit grant from the server owner: they bypass the global
 * {@code bbsEditing} gamerule / OP gate everywhere BBS uses
 * {@link PermissionUtils#arePanelsAllowed} (film manager data, model blocks, player forms, ...).
 * Level 0 is handled separately — it must stay read-only even when the gamerule is on, which
 * bbs-together enforces in its own packet handlers and {@code ServerNetworkBbsOpMixin}.
 */
@Mixin(value = PermissionUtils.class, remap = false)
public class PermissionUtilsBbsOpMixin
{
    @Inject(method = "arePanelsAllowed", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$grantEditing(MinecraftServer server, ServerPlayerEntity player, CallbackInfoReturnable<Boolean> cir)
    {
        if (player != null && BbsOpManager.get(player.getUuid()) >= 1)
        {
            cir.setReturnValue(true);
        }
    }
}
