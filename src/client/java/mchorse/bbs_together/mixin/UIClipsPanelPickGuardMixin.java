package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.locks.ClipLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses to open the properties or keyframe panel of a clip this player may not edit.
 *
 * <p>Every way into a clip's editor goes through {@code UIClipsPanel.pickClip}, including the
 * double click that embeds the keyframe editor, so blocking it here is what actually keeps
 * another player out of a visibly locked clip. The panel is only refused when it carries a
 * lock the player is not on the whitelist of; picking {@code null} (closing the panel) is
 * always allowed, as is a clip this player may edit.</p>
 */
@Mixin(value = UIClipsPanel.class, remap = false)
public class UIClipsPanelPickGuardMixin
{
    @Inject(method = "pickClip", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$refuseLockedClipPanel(Clip clip, CallbackInfo ci)
    {
        if (clip == null || ClipLockClient.canEdit(clip))
        {
            return;
        }

        ci.cancel();
    }
}
