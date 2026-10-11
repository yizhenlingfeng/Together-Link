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
 * greyed out and the whole bar wears the grey "view only" veil. The veil is attached once
 * and is click-through for left clicks and scrolling (browsing and selecting replays keep
 * working), swallowing only right-click context menus — and it tracks the permission by
 * itself, so a change from 0 to 1/2 turns it inert on the very next frame.
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
        this.addReplay.setEnabled(!!BbsOpClientState.canEdit("film"));

        ReadOnlyOverlay.ensureAttached((UIReplaysListPanel) (Object) this, this.together$overlay, true, "film");
    }
}
