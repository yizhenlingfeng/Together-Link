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
 * Read-only (level 0) backup gate on the form editor: even if some other path tries to enter
 * edit mode (the list's edit button is greyed out by {@link UIFormListBbsOpMixin}), entering
 * edit mode is refused. This also covers the morphing panel's immersive double-click →
 * confirm → toggleEditor path and the FORMS_EDIT keybind. Leaving edit mode (editing == false
 * via {@code edit(boolean)}) is always allowed so the panel can clean itself up.
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

    @Inject(method = "toggleEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockToggleEditor(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }
}
