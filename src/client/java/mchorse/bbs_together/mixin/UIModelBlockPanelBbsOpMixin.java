package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.ui.UIModelBlockLockSection;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the model block panel: the block list stays browsable, but
 * picking a block never fills the editing side, and the whole editing side (editor column,
 * body/equipment sections) is covered by one veil that both draws the lock look and swallows
 * clicks. The veils track the permission by themselves.
 *
 * <p>This also installs the level-2 asset-lock section under the "look at target" toggle and
 * refuses to fill the editor for a block another player locked.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIModelBlockPanel.class, remap = false)
public class UIModelBlockPanelBbsOpMixin
{
    /** Click shields over the editor column and the two sections, attached lazily. */
    @Unique
    private final UIElement[] together$shields = new UIElement[3];

    @Unique
    private UIModelBlockLockSection together$lockSection;

    @Shadow(remap = false)
    private ModelBlockEntity modelBlock;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void together$addLockSection(UIDashboard dashboard, CallbackInfo ci)
    {
        UIModelBlockPanel self = (UIModelBlockPanel) (Object) this;

        this.together$lockSection = new UIModelBlockLockSection(this::together$selectedBlock, () -> self.scrollView.resize());
        self.editor.addAfter(self.lookAt, this.together$lockSection);
    }

    @Unique
    private ModelBlockEntity together$selectedBlock()
    {
        return this.modelBlock;
    }

    @Inject(method = "fill(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFill(ModelBlockEntity modelBlock, boolean select, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("model_blocks"))
        {
            ci.cancel();

            return;
        }

        if (modelBlock != null)
        {
            String key = AssetLockManager.modelBlockId(modelBlock.getWorld(), modelBlock.getPos());

            if (!AssetLockClient.canEdit(AssetLockType.MODEL_BLOCK, key))
            {
                UIModelBlockPanel self = (UIModelBlockPanel) (Object) this;
                UIContext context = self.getContext();

                if (context != null)
                {
                    context.notifyError(L10n.lang("bbs_together.locks.notify.model_block_locked"));
                }

                self.fill(null, false);
                ci.cancel();
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$veilEditingSide(UIContext context, CallbackInfo ci)
    {
        UIModelBlockPanel self = (UIModelBlockPanel) (Object) this;
        boolean readOnly = !BbsOpClientState.canEdit("model_blocks");

        if (this.together$lockSection != null)
        {
            this.together$lockSection.refresh();
        }

        /* The pick button's callback dereferences this.modelBlock unconditionally; with fill
         * cancelled it is null, so the button must be dead or clicking it NPEs. */
        self.pickEdit.setEnabled(!readOnly);

        if (!readOnly)
        {
            return;
        }

        UIElement[] covered = { self.editor, self.bodySection, self.equipmentSection };

        /* One visible veil over the union of the covered areas: folded sections still fall
         * inside it, an unfolded one grows it, and it follows resizes every frame. */
        Area veil = new Area();
        boolean first = true;

        for (UIElement element : covered)
        {
            Area a = element.area;

            if (first)
            {
                veil.set(a.x, a.y, a.w, a.h);
                first = false;
            }
            else
            {
                veil.x = Math.min(veil.x, a.x);
                veil.y = Math.min(veil.y, a.y);
                veil.w = Math.max(veil.ex(), a.ex()) - veil.x;
                veil.h = Math.max(veil.ey(), a.ey()) - veil.y;
            }
        }

        ReadOnlyOverlay.drawVeil(context, veil);
        ReadOnlyOverlay.ensureShieldsAttached(self, this.together$shields, "model_blocks", covered);
    }
}
