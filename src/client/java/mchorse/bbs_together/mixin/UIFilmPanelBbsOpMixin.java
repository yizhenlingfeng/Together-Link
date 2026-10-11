package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only lock: a level-0 player cannot toggle camera flight. setFlight(false) on panel close
 * is left alone (that's cleanup), only the user-initiated toggle is blocked.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFilmPanel.class, remap = false)
public class UIFilmPanelBbsOpMixin
{
    @Inject(method = "toggleFlight", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockToggleFlight(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }
}
