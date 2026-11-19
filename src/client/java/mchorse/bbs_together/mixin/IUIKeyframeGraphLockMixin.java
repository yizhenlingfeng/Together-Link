package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

/**
 * Keyframes also appear without a click on them: the create-at-playhead shortcut, auto-keyframing on a
 * value edit, the "add keyframe" keybinding. They all end up in the channel insertion of the graph, not
 * in the keyframe view, so the lock has to be enforced here.
 *
 * <p>The sheet's keys are snapshotted before the insertion and diffed after it: anything that landed
 * inside a lock is removed again. Injecting the interface by its full descriptor keeps this working for
 * both graphs (dope sheet and curve graph), since the method is a default method of the interface.</p>
 */
@Mixin(value = IUIKeyframeGraph.class, remap = false)
public interface IUIKeyframeGraphLockMixin
{
    @Inject(
        method = "addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;",
        at = @At("HEAD"),
        remap = false
    )
    private void bbs_together$rememberKeys(UIKeyframeSheet sheet, float tick, Object value, CallbackInfoReturnable<Keyframe> cir)
    {
        ArrayList<Keyframe<?>> keys = new ArrayList<>(KeyframeLockClient.channelKeys(sheet));

        KeyframeLockClient.rememberInsertion(sheet, tick, keys);
    }

    @Inject(
        method = "addKeyframe(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet;FLjava/lang/Object;)Lmchorse/bbs_mod/utils/keyframes/Keyframe;",
        at = @At("RETURN"),
        remap = false
    )
    private void bbs_together$dropKeysInLock(UIKeyframeSheet sheet, float tick, Object value, CallbackInfoReturnable<Keyframe> cir)
    {
        KeyframeLockClient.dropLockedInsertions();
    }
}
