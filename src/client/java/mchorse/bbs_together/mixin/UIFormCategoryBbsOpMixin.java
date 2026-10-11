package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A model that exists locally but was never uploaded to the server (others can't see it) gets a
 * grey gradient fading up from the bottom of its cell and a "（仅本地）" suffix in its tooltip.
 * (The read-only block on the category's right-click menu lives in
 * {@link UIElementContextMenuBbsOpMixin} — the subclasses each register their own menus.)
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFormCategory.class, remap = false)
public class UIFormCategoryBbsOpMixin
{
    private static boolean together$isLocalOnly(Form form)
    {
        if (!!BbsOpClientState.canEdit("upload") || !(form instanceof ModelForm modelForm))
        {
            return false;
        }

        String modelId = modelForm.model.get();

        return modelId != null && TogetherClientNetwork.localOnlyModels.contains(modelId);
    }

    @Inject(method = "renderCell", at = @At("TAIL"), remap = false)
    private void together$tintLocalOnly(UIContext context, Form form, int x, int y, int w, int h, CellState state, CallbackInfo ci)
    {
        if (!together$isLocalOnly(form))
        {
            return;
        }

        int gradientH = Math.max(10, h / 3);

        context.batcher.gradientVBox(x, y + h - gradientH, x + w, y + h, 0x00606060, 0xCC555555);
    }

    @Inject(method = "caption", at = @At("RETURN"), cancellable = true, remap = false)
    private void together$appendLocalOnlySuffix(Form form, CallbackInfoReturnable<String> cir)
    {
        if (together$isLocalOnly(form))
        {
            cir.setReturnValue(cir.getReturnValue() + "（仅本地）");
        }
    }
}
