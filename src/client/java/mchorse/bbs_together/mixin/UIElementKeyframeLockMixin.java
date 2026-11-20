package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = UIElement.class, remap = false)
public abstract class UIElementKeyframeLockMixin
{
    private boolean bbs_together$lockedPanel()
    {
        return (Object) this instanceof UIKeyframeFactory<?> factory && KeyframeLockClient.lockedKeyframe(factory.getKeyframe());
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$skipLockedRender(UIContext context, CallbackInfo ci)
    {
        if (this.bbs_together$lockedPanel())
        {
            ci.cancel();
        }
    }

    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedClick(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbs_together$lockedPanel())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "subMouseReleased", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedRelease(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbs_together$lockedPanel())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "subMouseScrolled", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedScroll(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbs_together$lockedPanel())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "subKeyPressed", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedKey(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.bbs_together$lockedPanel())
        {
            cir.setReturnValue(true);
        }
    }
}
