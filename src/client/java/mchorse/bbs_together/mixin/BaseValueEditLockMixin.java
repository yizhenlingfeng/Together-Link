package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(value = BaseValue.class, remap = false)
public abstract class BaseValueEditLockMixin
{
    @Inject(method = "preNotify(Lmchorse/bbs_mod/settings/values/base/BaseValue;I)V", at = @At("HEAD"), remap = false)
    private void bbs_together$beginLockedEdit(BaseValue value, int flag, CallbackInfo ci)
    {
        if ((Object) this instanceof KeyframeChannel<?> channel)
        {
            KeyframeLockClient.beginChannelEdit(channel);
        }
    }

    @Inject(method = "postNotify(Lmchorse/bbs_mod/settings/values/base/BaseValue;I)V", at = @At("HEAD"), remap = false)
    private void bbs_together$endLockedEdit(BaseValue value, int flag, CallbackInfo ci)
    {
        if ((Object) this instanceof KeyframeChannel<?> channel)
        {
            KeyframeLockClient.endChannelEdit(channel);
        }
    }

    @Inject(method = "edit(Lmchorse/bbs_mod/settings/values/base/BaseValue;ILjava/util/function/Consumer;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T extends BaseValue> void bbs_together$lockDirectEdit(T value, int flag, Consumer<T> callback, CallbackInfo ci)
    {
        if (value instanceof Keyframe<?> key && KeyframeLockClient.blocksKeyframeMutation(key))
        {
            ci.cancel();
        }
    }
}
