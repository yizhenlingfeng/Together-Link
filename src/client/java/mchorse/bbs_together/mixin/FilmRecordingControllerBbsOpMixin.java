package mchorse.bbs_together.mixin;

import java.util.List;

import mchorse.bbs_mod.ui.film.controller.FilmRecordingController;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only lock: a level-0 player cannot start recording replay actions. The server would drop
 * the resulting RECORDING_FINISHED packet anyway; this stops it earlier and silently.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = FilmRecordingController.class, remap = false)
public class FilmRecordingControllerBbsOpMixin
{
    @Inject(method = "startRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockStartRecording(List<String> groups, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly()) ci.cancel();
    }
}
