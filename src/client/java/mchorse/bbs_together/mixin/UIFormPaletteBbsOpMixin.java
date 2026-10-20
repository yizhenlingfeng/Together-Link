package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Backup gate on the form editor. Entering edit mode is refused for level 0 and for a
 * selected disguise whose model is locked against this player. This covers the list's edit
 * button, the morphing panel's immersive double-click → confirm → toggleEditor path and the
 * FORMS_EDIT keybind. Leaving edit mode is always allowed, so a lock applied while the editor
 * is open cannot trap the user inside it.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFormPalette.class, remap = false)
public class UIFormPaletteBbsOpMixin
{
    @Inject(method = "edit(Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockEnterEdit(boolean editing, CallbackInfo ci)
    {
        UIFormPalette self = (UIFormPalette) (Object) this;

        if (editing && !together$mayEnterEditor(self))
        {
            ci.cancel();
        }
    }

    @Inject(method = "toggleEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockToggleEditor(CallbackInfo ci)
    {
        UIFormPalette self = (UIFormPalette) (Object) this;

        /* Only entering is gated: exiting must stay possible after a permission/lock change. */
        if (!self.editor.isEditing() && !together$mayEnterEditor(self))
        {
            ci.cancel();
        }
    }

    private static boolean together$mayEnterEditor(UIFormPalette palette)
    {
        if (!BbsOpClientState.canEdit("morph"))
        {
            return false;
        }

        Form form = palette.list.getSelected();

        if (form instanceof ModelForm modelForm)
        {
            String id = modelForm.model.get();

            return id == null || (AssetLockClient.canEdit(AssetLockType.MODEL, id)
                && BbsOpClientState.canEdit("model_editor"));
        }

        return true;
    }
}
