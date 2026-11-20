package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.clips.UIKeyframeClip;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIKeyframeClip.class, remap = false)
public abstract class UIKeyframeClipLockMixin
{
    @Inject(method = "insertKeyframe", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedFlightInsert(float tick, KeyframeChannel<Double> channel, double x, CallbackInfo ci)
    {
        if (KeyframeLockClient.lockedTick(channel, tick))
        {
            ci.cancel();
        }
    }
}
