package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Null guard (skip rendering without a film) merged with the read-only (level 0) lock on
 * the preview toolbar: the actor-control toggle, the replay recording button and the video
 * recording button are disabled (grey icon, no clicks) so a level-0 player can neither edit
 * actors nor start any kind of recording. Playback-only buttons (play/pause, perspective,
 * teleport, onion skin, motion path) stay usable.
 */
@Environment(EnvType.CLIENT)
@Mixin(value={UIFilmPreview.class}, remap=false)
public class UIFilmPreviewNullGuardMixin {
    @Shadow
    private UIFilmPanel panel;

    @Inject(method={"render"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together_fix132SkipRenderWithoutFilm(UIContext uIContext, CallbackInfo callbackInfo) {
        if (this.panel == null || this.panel.getData() == null) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$disableEditingButtons(UIContext context, CallbackInfo ci)
    {
        boolean readOnly = !BbsOpClientState.canEdit("film");
        UIFilmPreview self = (UIFilmPreview) (Object) this;

        self.control.setEnabled(!readOnly);
        self.recordReplay.setEnabled(!readOnly);
        self.recordVideo.setEnabled(!readOnly);

        if (readOnly)
        {
            /* A small padlock over each disabled icon, same style as renderLockedArea. */
            context.batcher.outlinedIcon(Icons.LOCKED, self.control.area.mx(), self.control.area.my(), 0.5F, 0.5F);
            context.batcher.outlinedIcon(Icons.LOCKED, self.recordReplay.area.mx(), self.recordReplay.area.my(), 0.5F, 0.5F);
            context.batcher.outlinedIcon(Icons.LOCKED, self.recordVideo.area.mx(), self.recordVideo.area.my(), 0.5F, 0.5F);
        }
    }
}
