package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side counterpart of the recording gate: {@code ActionManager.startRecording} is
 * what BBS's own action-recording packet path drives (the client announces a take via
 * {@code sendActionRecording}, the server records world-side action clips). A tampered
 * client that skips the local UI gates must not be able to start a server-side recording
 * session either: both the film domain and the record sub-permission are required.
 *
 * <p>Targeted by bare method name — {@code ActionManager} has exactly one
 * {@code startRecording}, and a name-only reference avoids embedding a mapped Minecraft
 * class in the descriptor.</p>
 */
@Mixin(value = ActionManager.class, remap = false)
public class ActionManagerBbsOpMixin
{
    @Inject(method = "startRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockActionRecording(Film film, ServerPlayerEntity entity, int tick, int countdown, int replayId, CallbackInfo ci)
    {
        if (!BbsOpManager.isAllowed(entity, BbsOpDomain.FILM) || !BbsOpManager.isAllowed(entity, BbsOpDomain.RECORD))
        {
            ci.cancel();
        }
    }
}
