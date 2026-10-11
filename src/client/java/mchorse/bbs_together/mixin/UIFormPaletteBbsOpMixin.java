package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) backup gate: even if some other path tries to enter the form editor
 * (the button is greyed out by {@link UIFormListBbsOpMixin}), entering edit mode is refused.
 * Leaving edit mode (editing == false) is always allowed so the panel can clean itself up.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFormPalette.class, remap = false)
public class UIFormPaletteBbsOpMixin
{
    @Inject(method = "edit(Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockEnterEdit(boolean editing, CallbackInfo ci)
    {
        if (editing && BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }
}
