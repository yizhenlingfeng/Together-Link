package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.items.UIItems;
import mchorse.bbs_mod.ui.framework.elements.input.list.UILikeableStringList;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_together.ui.OversizedRowPainter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The sound picker's list ({@link UILikeableStringList}) draws its own rows and never calls
 * {@code UIList.renderElementPart}, so it needs its own hook to get the "too large" decoration
 * for over-size audio files.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UILikeableStringList.class, remap = false)
public abstract class UILikeableStringListOversizedMixin
{
    @Inject(method = "renderElementPart", at = @At("TAIL"), remap = false)
    private void together$paintOversized(UIContext context, String element, int i, int x, int y, boolean hover, boolean selected, CallbackInfo ci)
    {
        int h = ((UIItems<?>) (Object) this).scroll.scrollItemSize;
        Area area = ((UIElement) (Object) this).area;

        OversizedRowPainter.paint(context, element, x, y, area.w, h);
    }
}
