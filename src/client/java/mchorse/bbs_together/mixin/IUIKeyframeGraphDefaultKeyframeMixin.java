package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.DefaultKeyframeStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={IUIKeyframeGraph.class}, remap=false)
public interface IUIKeyframeGraphDefaultKeyframeMixin {
    @Inject(method={"addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"}, at={@At(value="HEAD")}, remap=false)
    private void togetherTrackNewKeyframe(UIKeyframeSheet uIKeyframeSheet, float f, Object object, CallbackInfoReturnable<Keyframe> callbackInfoReturnable) {
        DefaultKeyframeStyle.beginAddKeyframe(uIKeyframeSheet, f);
    }

    @Inject(method={"addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;"}, at={@At(value="RETURN")}, remap=false)
    private void togetherApplyDefaultKeyframeStyle(UIKeyframeSheet uIKeyframeSheet, float f, Object object, CallbackInfoReturnable<Keyframe> callbackInfoReturnable) {
        DefaultKeyframeStyle.applyDefaultsToNewKeyframe((Keyframe)callbackInfoReturnable.getReturnValue());
    }
}

