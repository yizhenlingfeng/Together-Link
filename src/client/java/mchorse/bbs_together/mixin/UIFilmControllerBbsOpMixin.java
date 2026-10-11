package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keyboard-side twin of UIFilmPreviewBbsOpMixin: the same three actions (toggle actor
 * control, pick replay recording) are reachable via editor keybinds, so the controller
 * entry points are cancelled for level-0 players too. Video recording goes through
 * UIFilmPreview's button and is covered there.
 */
@Mixin(value = UIFilmController.class, remap = false)
public class UIFilmControllerBbsOpMixin
{
    @Inject(method = "toggleControl", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockToggleControl(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "pickRecording", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPickRecording(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }
}
