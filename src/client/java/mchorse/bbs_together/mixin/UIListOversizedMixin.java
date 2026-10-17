package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.items.UIItems;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_together.ui.OversizedRowPainter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dims list rows that point at an asset too big to sync (video picker, link lists, and the sound
 * list's parent class). The row is painted after its normal text, see
 * {@link OversizedRowPainter} for what the decoration looks like.
 *
 * <p>Every row of every {@code UIList} passes through here, so the fast path is one
 * {@code isEmpty()} check plus, for strings, one hash lookup.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIList.class, remap = false)
public abstract class UIListOversizedMixin
{
    @Inject(method = "renderElementPart", at = @At("TAIL"), remap = false)
    private void together$paintOversized(UIContext context, Object element, int i, int x, int y, boolean hover, boolean selected, CallbackInfo ci)
    {
        /* The scroll lives on UIItems and the area on UIElement, i.e. not in this class. */
        int h = ((UIItems<?>) (Object) this).scroll.scrollItemSize;
        Area area = ((UIElement) (Object) this).area;

        OversizedRowPainter.paint(context, element, x, y, area.w, h);
    }
}
