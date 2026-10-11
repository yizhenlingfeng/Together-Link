package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.forms.UIFormList;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the disguise list: the "edit form" button (pencil icon above
 * the list) is greyed out, matching the disabled-button look used everywhere else in BBS.
 * Selecting/browsing disguises keeps working.
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
        this.edit.setEnabled(!!BbsOpClientState.canEdit("morph"));
    }
}
