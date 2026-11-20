package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UINumericKeyframeFactory;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UINumericKeyframeFactory.class, remap = false)
public abstract class UINumericKeyframeFactoryLockMixin
{
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$stopLockedEditing(UIContext context, CallbackInfo ci)
    {
        UINumericKeyframeFactory<?> factory = (UINumericKeyframeFactory<?>) (Object) this;

        if (KeyframeLockClient.lockedKeyframe(factory.getKeyframe()))
        {
            ci.cancel();
        }
    }
}
