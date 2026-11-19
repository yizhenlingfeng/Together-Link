package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.locks.KeyframeLockClient;
import mchorse.bbs_together.ui.KeyframeLockOverlay;
import mchorse.bbs_together.ui.KeyframeLockUi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Adds the keyframe-lock menu button/overlay and keeps locked keyframes out of
 * editing. This is a common client mixin: the touched 2.7 signatures are the same
 * for 1.20.1 and 1.21.11.
 */
@Mixin(value = UIKeyframes.class, remap = false)
public abstract class UIKeyframesLockMixin
{
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void bbs_together$addLockMenu(Consumer<Keyframe> callback, CallbackInfo ci)
    {
        ((UIElement) (Object) this).context((menu) -> KeyframeLockUi.install(menu, (UIKeyframes) (Object) this));
    }

    @Inject(method = "renderOverlay", at = @At("TAIL"), remap = false)
    private void bbs_together$renderLocks(UIContext context, CallbackInfo ci)
    {
        KeyframeLockOverlay.render((UIKeyframes) (Object) this, context);
    }

    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedClick(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (context.mouseButton == 0 && !KeyframeLockClient.hits((UIKeyframes) (Object) this, context.mouseX, context.mouseY).isEmpty())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handleMouse", at = @At("HEAD"), remap = false)
    private void bbs_together$filterLockedDrag(UIContext context, CallbackInfo ci)
    {
        KeyframeLockClient.filterLockedSelection((UIKeyframes) (Object) this);
    }

    @Inject(method = "subMouseReleased", at = @At("TAIL"), remap = false)
    private void bbs_together$filterLockedRelease(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        KeyframeLockClient.filterLockedSelection((UIKeyframes) (Object) this);
    }

    @Inject(method = "subKeyPressed", at = @At("HEAD"), remap = false)
    private void bbs_together$filterLockedKeys(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        KeyframeLockClient.filterLockedSelection((UIKeyframes) (Object) this);
    }
}
