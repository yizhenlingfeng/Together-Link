package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.UIFormList;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only lock on the disguise list's "edit form" button (pencil icon above the list).
 * It is greyed out for level 0 and also when the selected disguise is a locked model the
 * player cannot edit. Selecting/browsing disguises keeps working.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFormList.class, remap = false)
public class UIFormListBbsOpMixin
{
    @Shadow(remap = false)
    public UIIcon edit;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$greyOutEdit(UIContext context, CallbackInfo ci)
    {
        UIFormList self = (UIFormList) (Object) this;
        boolean editable = BbsOpClientState.canEdit("morph");

        if (editable)
        {
            Form form = self.getSelected();

            if (form instanceof ModelForm modelForm)
            {
                String id = modelForm.model.get();

                if (id != null && !AssetLockClient.canEdit(AssetLockType.MODEL, id))
                {
                    editable = false;
                }
            }
        }

        this.edit.setEnabled(editable);
    }
}
