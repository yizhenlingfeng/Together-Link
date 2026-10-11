package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * Domain-aware lock on the bottom navigation: the particle / model-editor / texture / audio
 * panel buttons are disabled (with a small padlock badge) unless the local player may edit
 * their respective {@code BbsOpDomain} — level 0 never can, a restricted level-1 editor
 * only when the domain is granted, level 2 always.
 */
@Environment(value = EnvType.CLIENT)
@Mixin(value = UIDashboardPanels.class, remap = false)
public class UIDashboardPanelsBbsOpMixin
{
    /** Panel class name -> the editable domain that panel belongs to. */
    private static final Map<String, String> together$panelDomains = Map.of(
        "mchorse.bbs_mod.ui.particles.UIParticleSchemePanel", "particles",
        "mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel", "model_editor",
        "mchorse.bbs_mod.ui.dashboard.textures.UITextureManagerPanel", "textures",
        "mchorse.bbs_mod.ui.utility.audio.UIAudioEditorPanel", "audio");

    private final List<UIIcon> together$lockedIcons = new ArrayList<>();
    private final List<String> together$iconDomains = new ArrayList<>();

    @Inject(method = "registerPanel", at = @At("TAIL"), remap = false)
    private void together$collectLockedIcon(UIDashboardPanel panel, IKey tooltip, Icon icon, CallbackInfoReturnable<UIIcon> cir)
    {
        if (panel != null)
        {
            String domain = together$panelDomains.get(panel.getClass().getName());

            if (domain != null)
            {
                this.together$lockedIcons.add(cir.getReturnValue());
                this.together$iconDomains.add(domain);
            }
        }
    }

    @Inject(method = "renderBackground", at = @At("TAIL"), remap = false)
    private void together$applyLock(UIContext context, CallbackInfo ci)
    {
        for (int i = 0; i < this.together$lockedIcons.size(); i++)
        {
            UIIcon button = this.together$lockedIcons.get(i);
            boolean locked = !BbsOpClientState.canEdit(this.together$iconDomains.get(i));

            button.setEnabled(!locked);

            if (locked)
            {
                Area a = button.area;

                context.batcher.icon(Icons.LOCKED, Colors.WHITE, a.ex() - 6, a.ey() - 6, 0.5F, 0.5F);
            }
        }
    }
}
