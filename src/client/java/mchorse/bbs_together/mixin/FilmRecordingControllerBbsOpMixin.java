package mchorse.bbs_together.mixin;

import java.util.List;

import mchorse.bbs_mod.ui.film.controller.FilmRecordingController;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gate on the film editor's recording machinery: starting a take requires both the film
 * domain and the record sub-permission. The UI entries (the record button and the record
 * hotkey) are gated upstream, this stops anything that would reach the method directly.
 * The server drops the resulting RECORDING_FINISHED packet anyway; this stops it earlier
 * and silently. Cancelling here also keeps the internal {@code toggleControl} call from
 * ever running, so no controlled-entity state is left behind.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = FilmRecordingController.class, remap = false)
public class FilmRecordingControllerBbsOpMixin
{
    @Inject(method = "startRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockStartRecording(List<String> groups, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit(BbsOpDomain.FILM.id) || !BbsOpClientState.canEdit(BbsOpDomain.RECORD.id))
        {
            ci.cancel();
        }
    }
}
