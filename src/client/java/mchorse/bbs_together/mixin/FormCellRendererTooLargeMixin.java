package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.forms.FormCellRenderer;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = FormCellRenderer.class, remap = false)
public abstract class FormCellRendererTooLargeMixin
{
    private static final int TOP_RED = 0xCC_8B0000;
    private static final int BOT_RED = 0xCC_D02020;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void together$paintTooLarge(UIContext context, Form form, int x, int y, int w, int h, CellState state, CallbackInfo ci)
    {
        if (!(form instanceof ModelForm modelForm)) return;
        String id = modelForm.model.get();
        if (id == null || !TogetherClientNetwork.tooLargeModels.contains(id)) return;

        context.batcher.gradientVBox(x, y, x + w, y + h, TOP_RED, BOT_RED);

        FontRenderer font = context.batcher.getFont();
        String label = "模型过大";
        int tx = x + (w - font.getWidth(label)) / 2;
        int ty = y + (h - font.getHeight()) / 2;
        context.batcher.text(label, tx, ty, 0xFFFFFFFF, true);
    }
}
