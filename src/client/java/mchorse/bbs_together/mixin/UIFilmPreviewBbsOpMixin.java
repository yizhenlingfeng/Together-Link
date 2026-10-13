package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Visual lock on the film preview toolbar's two record buttons, in the same spirit as the
 * read-only veils elsewhere: a player without both the film and the record sub-permission
 * sees them greyed out ({@code disabledColor}) and dead, instead of clicking into a
 * silently cancelled action.
 *
 * <p>{@code recordReplay} opens the replay recording overlay (already gated downstream);
 * {@code recordVideo} is the video/screenshot export button — BBS's second "record" that
 * writes a video file locally and never touches the server, so the button lock is the only
 * gate it can have. Both are re-evaluated every frame, so a live permission change greys
 * or frees them without reopening the panel.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFilmPreview.class, remap = false)
public class UIFilmPreviewBbsOpMixin
{
    @Shadow(remap = false)
    public UIIcon recordReplay;

    @Shadow(remap = false)
    public UIIcon recordVideo;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$lockRecordButtons(UIContext context, CallbackInfo ci)
    {
        boolean canRecord = BbsOpClientState.canEdit(BbsOpDomain.FILM.id) && BbsOpClientState.canEdit(BbsOpDomain.RECORD.id);

        this.recordReplay.setEnabled(canRecord);
        this.recordVideo.setEnabled(canRecord);
    }
}
