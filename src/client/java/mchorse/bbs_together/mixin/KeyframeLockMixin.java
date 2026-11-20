package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeStyle;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Keyframe.class, remap = false)
public abstract class KeyframeLockMixin
{
    @Inject(method = "setTick(FZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockTick(float tick, boolean dirty, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this, tick))
        {
            ci.cancel();
        }
    }

    @Inject(method = "setValue(Ljava/lang/Object;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockValue(Object value, boolean dirty, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this))
        {
            ci.cancel();
        }
    }

    @Inject(method = "setDuration(F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockDuration(float duration, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this))
        {
            ci.cancel();
        }
    }

    @Inject(method = "setMotionShift(FZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockMotionShift(float shift, boolean dirty, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this))
        {
            ci.cancel();
        }
    }

    @Inject(method = "setStyle(Lmchorse/bbs_mod/utils/keyframes/KeyframeStyle;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockStyle(KeyframeStyle style, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this))
        {
            ci.cancel();
        }
    }

    @Inject(method = "copy(Lmchorse/bbs_mod/utils/keyframes/Keyframe;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockCopy(Keyframe<?> keyframe, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this, keyframe.getTick()))
        {
            ci.cancel();
        }
    }

    @Inject(method = "copyOverExtra(Lmchorse/bbs_mod/utils/keyframes/Keyframe;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockExtra(Keyframe<?> keyframe, CallbackInfo ci)
    {
        if (KeyframeLockClient.blocksKeyframeMutation((Keyframe<?>) (Object) this))
        {
            ci.cancel();
        }
    }

    @Inject(method = "fromData(Lmchorse/bbs_mod/data/types/BaseType;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$lockData(BaseType data, CallbackInfo ci)
    {
        Keyframe<?> key = (Keyframe<?>) (Object) this;
        float tick = data != null && data.isMap() && data.asMap().has("tick") ? data.asMap().getFloat("tick") : key.getTick();

        if (KeyframeLockClient.blocksKeyframeMutation(key, tick))
        {
            ci.cancel();
        }
    }
}
