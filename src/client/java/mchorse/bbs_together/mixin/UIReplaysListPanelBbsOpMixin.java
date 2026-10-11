package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the replay list toolbar: the "add replay" (+) button is
 * greyed out and the whole bar wears the grey "view only" veil. The veil is click-through
 * for left clicks and scrolling, so browsing and selecting replays still work; right
 * clicks (context menus) are swallowed.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIReplaysListPanel.class, remap = false)
public class UIReplaysListPanelBbsOpMixin
{
    @Shadow(remap = false)
    public UIIcon addReplay;

    @Unique
    private final UIElement[] together$overlay = new UIElement[1];

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$greyOutAddAndVeil(UIContext context, CallbackInfo ci)
    {
        boolean readOnly = BbsOpClientState.isReadOnly();

        this.addReplay.setEnabled(!readOnly);

        UIReplaysListPanel self = (UIReplaysListPanel) (Object) this;

        ReadOnlyOverlay.ensureAttached(self, this.together$overlay, readOnly, true);
    }
}
