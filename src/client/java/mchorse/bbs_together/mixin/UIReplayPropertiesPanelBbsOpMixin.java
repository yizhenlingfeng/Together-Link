package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropertiesPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Read-only (level 0) lock on the replay properties panel: the grey "view only" veil swallows
 * every click and scroll aimed at the property widgets, and the programmatic edit entry point
 * is short-circuited. Selecting a different replay still works (the replay list stays live).
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIReplayPropertiesPanel.class, remap = false)
public class UIReplayPropertiesPanelBbsOpMixin
{
    @Unique
    private final UIElement[] together$overlay = new UIElement[1];

    @Inject(method = "edit", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockEdit(Consumer<Replay> consumer, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "setReplay", at = @At("TAIL"), remap = false)
    private void together$attachOverlay(Replay replay, CallbackInfo ci)
    {
        UIReplayPropertiesPanel self = (UIReplayPropertiesPanel) (Object) this;

        ReadOnlyOverlay.ensureAttached(self, this.together$overlay, BbsOpClientState.isReadOnly(), false);
    }
}
