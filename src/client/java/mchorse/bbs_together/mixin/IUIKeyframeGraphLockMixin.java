package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = IUIKeyframeGraph.class, remap = false)
public interface IUIKeyframeGraphLockMixin
{
    @Inject(
        method = "addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void bbs_together$blockLockedInsertion(UIKeyframeSheet sheet, float tick, Object value, CallbackInfoReturnable<Keyframe> cir)
    {
        if (KeyframeLockClient.lockedTick(sheet, tick))
        {
            cir.setReturnValue(null);
        }
    }

    @Inject(
        method = "removeKeyframe(Lmchorse/bbs_mod/utils/keyframes/Keyframe;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void bbs_together$blockLockedRemoval(Keyframe keyframe, CallbackInfo ci)
    {
        if (KeyframeLockClient.lockedKeyframe(keyframe))
        {
            ci.cancel();
        }
    }
}
