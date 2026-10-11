package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
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
    private final UIElement[] together$editorOverlay = new UIElement[1];

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
    private void together$veilEditingSide(CallbackInfo ci)
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

        ReadOnlyOverlay.ensureAttached(self.editor, this.together$editorOverlay, false);
        ReadOnlyOverlay.ensureAttached(self.bodySection, this.together$bodyOverlay, false);
        ReadOnlyOverlay.ensureAttached(self.equipmentSection, this.together$equipmentOverlay, false);
    }
}
