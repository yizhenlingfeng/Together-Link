package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.replays.UIReplayPropTransform;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIReplayPropTransform.class, remap = false)
public abstract class UIReplayPropTransformLockMixin
{
    @Shadow
    private Replay replay;

    @Shadow
    private float writeTick()
    {
        return 0;
    }

    private boolean bbs_together$lockedTranslation()
    {
        if (this.replay == null)
        {
            return false;
        }

        float tick = this.writeTick();

        return KeyframeLockClient.lockedTick(this.replay.keyframes.x, tick)
            || KeyframeLockClient.lockedTick(this.replay.keyframes.y, tick)
            || KeyframeLockClient.lockedTick(this.replay.keyframes.z, tick);
    }

    private boolean bbs_together$lockedRotation()
    {
        if (this.replay == null)
        {
            return false;
        }

        float tick = this.writeTick();

        return KeyframeLockClient.lockedTick(this.replay.keyframes.yaw, tick)
            || KeyframeLockClient.lockedTick(this.replay.keyframes.headYaw, tick)
            || KeyframeLockClient.lockedTick(this.replay.keyframes.bodyYaw, tick)
            || KeyframeLockClient.lockedTick(this.replay.keyframes.pitch, tick);
    }

    @Inject(method = "setT", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedTranslation(Axis axis, double x, double y, double z, CallbackInfo ci)
    {
        if (this.bbs_together$lockedTranslation())
        {
            ci.cancel();
        }
    }

    @Inject(method = "setR", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedRotation(Axis axis, double x, double y, double z, CallbackInfo ci)
    {
        if (this.bbs_together$lockedRotation())
        {
            ci.cancel();
        }
    }
}
