package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the model block panel: the world can still be browsed (the
 * panel's camera and right-click orbit stay alive), but picking a model block never fills
 * the editing side — its area is covered by the grey "view only" veil instead.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIModelBlockPanel.class, remap = false)
public class UIModelBlockPanelBbsOpMixin
{
    @Inject(method = "fill(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFill(ModelBlockEntity modelBlock, boolean select, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$drawVeil(UIContext context, CallbackInfo ci)
    {
        if (!BbsOpClientState.isReadOnly())
        {
            return;
        }

        UIModelBlockPanel self = (UIModelBlockPanel) (Object) this;

        ReadOnlyOverlay.drawVeil(context, self.pickEdit.area);
    }
}
