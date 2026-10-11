package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the model block panel: the block list stays browsable, but
 * picking a model block never fills the editing side, and the whole editing side (nested
 * edit button, toggles, transform, body/equipment sections) is covered by real overlay
 * elements — the veil both draws the lock look and swallows clicks, so the properties
 * underneath cannot be edited. The overlays track the permission by themselves.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIModelBlockPanel.class, remap = false)
public class UIModelBlockPanelBbsOpMixin
{
    @Unique
    private final UIElement[] together$bodyOverlay = new UIElement[1];

    @Unique
    private final UIElement[] together$equipmentOverlay = new UIElement[1];

    @Inject(method = "fill(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFill(ModelBlockEntity modelBlock, boolean select, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$veilEditingSide(UIContext context, CallbackInfo ci)
    {
        boolean readOnly = BbsOpClientState.isReadOnly();
        UIModelBlockPanel self = (UIModelBlockPanel) (Object) this;

        /* The pick button's callback dereferences this.modelBlock unconditionally; with fill
         * cancelled it is null, so the button itself must be dead or clicking it NPEs. */
        self.pickEdit.setEnabled(!readOnly);

        if (!readOnly)
        {
            return;
        }

        /* One visible veil spanning the editing column AND the body/equipment sections
         * (union of their live areas, so folded rows are covered too and an unfolded
         * section is swallowed by the growing veil); it follows resizes every frame. */
        Area veil = new Area();
        Area editor = self.editor.area;
        Area body = self.bodySection.area;
        Area equipment = self.equipmentSection.area;
        int ex = Math.max(editor.ex(), Math.max(body.ex(), equipment.ex()));
        int ey = Math.max(editor.ey(), Math.max(body.ey(), equipment.ey()));

        veil.x = Math.min(editor.x, Math.min(body.x, equipment.x));
        veil.y = Math.min(editor.y, Math.min(body.y, equipment.y));
        veil.w = Math.max(0, ex - veil.x);
        veil.h = Math.max(0, ey - veil.y);

        ReadOnlyOverlay.drawVeil(context, veil);
        ReadOnlyOverlay.ensureShieldAttached(self, this.together$bodyOverlay, self.bodySection);
        ReadOnlyOverlay.ensureShieldAttached(self, this.together$equipmentOverlay, self.equipmentSection);
    }
}
