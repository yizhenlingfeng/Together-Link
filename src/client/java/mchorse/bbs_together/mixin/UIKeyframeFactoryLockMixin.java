package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = UIKeyframeFactory.class, remap = false)
public abstract class UIKeyframeFactoryLockMixin
{
    @Inject(method = "createPanel(Lmchorse/bbs_mod/utils/keyframes/Keyframe;Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframes;)Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIKeyframeFactory;", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T> void bbs_together$noLockedPanel(Keyframe<T> keyframe, UIKeyframes editor, CallbackInfoReturnable<UIKeyframeFactory> cir)
    {
        if (KeyframeLockClient.lockedKeyframe(keyframe))
        {
            cir.setReturnValue(null);
        }
    }
}
