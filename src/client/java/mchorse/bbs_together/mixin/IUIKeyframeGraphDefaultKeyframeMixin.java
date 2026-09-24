package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.BBSTogetherFix65;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={IUIKeyframeGraph.class}, remap=false)
public interface IUIKeyframeGraphDefaultKeyframeMixin {
    @Inject(method={"addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix65TrackNewKeyframe(UIKeyframeSheet uIKeyframeSheet, float f, Object object, CallbackInfoReturnable<Keyframe> callbackInfoReturnable) {
        BBSTogetherFix65.beginAddKeyframe(uIKeyframeSheet, f);
    }

    @Inject(method={"addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"}, at={@At(value="RETURN")}, remap=false)
    private void together_fix65ApplyDefaultKeyframeStyle(UIKeyframeSheet uIKeyframeSheet, float f, Object object, CallbackInfoReturnable<Keyframe> callbackInfoReturnable) {
        BBSTogetherFix65.applyDefaultsToNewKeyframe((Keyframe)callbackInfoReturnable.getReturnValue());
    }
}

