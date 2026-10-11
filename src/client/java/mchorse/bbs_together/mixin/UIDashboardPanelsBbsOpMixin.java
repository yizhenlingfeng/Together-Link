package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.List;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-only lock on the bottom navigation: while the local player is level 0, the particle /
 * model-editor / texture / audio panel buttons are disabled and get a small padlock badge.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIDashboardPanels.class, remap = false)
public class UIDashboardPanelsBbsOpMixin
{
    private static final List<String> together$lockedPanels = List.of(
        "mchorse.bbs_mod.ui.particles.UIParticleSchemePanel",
        "mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel",
        "mchorse.bbs_mod.ui.dashboard.textures.UITextureManagerPanel",
        "mchorse.bbs_mod.ui.utility.audio.UIAudioEditorPanel");

    private final List<UIIcon> together$lockedIcons = new ArrayList<>();

    @Inject(method = "registerPanel", at = @At("TAIL"), remap = false)
    private void together$collectLockedIcon(UIDashboardPanel panel, IKey tooltip, Icon icon, CallbackInfoReturnable<UIIcon> cir)
    {
        if (panel != null && together$lockedPanels.contains(panel.getClass().getName()))
        {
            this.together$lockedIcons.add(cir.getReturnValue());
        }
    }

    @Inject(method = "renderBackground", at = @At("TAIL"), remap = false)
    private void together$applyLock(UIContext context, CallbackInfo ci)
    {
        boolean readOnly = BbsOpClientState.isReadOnly();

        for (UIIcon button : this.together$lockedIcons)
        {
            button.setEnabled(!readOnly);

            if (readOnly)
            {
                Area a = button.area;

                context.batcher.icon(Icons.LOCKED, Colors.WHITE, a.ex() - 6, a.ey() - 6, 0.5F, 0.5F);
            }
        }
    }
}
