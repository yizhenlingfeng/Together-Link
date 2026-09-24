package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}

