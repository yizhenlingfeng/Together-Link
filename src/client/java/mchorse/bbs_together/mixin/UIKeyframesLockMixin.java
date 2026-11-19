package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.locks.KeyframeLockClient;
import mchorse.bbs_together.ui.KeyframeLockOverlay;
import mchorse.bbs_together.ui.KeyframeLockUi;
import mchorse.bbs_mod.graphics.window.Window;
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
 *
 * <p>Dropping locked keys from the selection is not enough on its own: BBS picks the key
 * under the cursor and arms a drag on the same click, and a drag does not need a selection
 * to start. The click is therefore swallowed after the pick ran, which leaves nothing for
 * the following {@code UIKeyframes#handleMouse} to drag. The server does the same check
 * again for network edits; this path covers single player and the integrated server, where
 * the local player edits the working film directly and never hits the guard.</p>
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

    /**
     * A click that lands on a locked key or inside a lock block never reaches the selection:
     * BBS has already picked the key, so it is dropped here and the call reports the input as
     * consumed, which also disarms the drag arming done by the same click.
     */
    @Inject(method = "subMouseClicked", at = @At("RETURN"), cancellable = true, remap = false)
    private void bbs_together$blockLockedClick(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (context.mouseButton == 0 && KeyframeLockClient.filterLockedClick((UIKeyframes) (Object) this, context.mouseX, context.mouseY))
        {
            cir.setReturnValue(true);
        }
    }

    /**
     * Ctrl+click takes a different branch in BBS: it removes the keyframe under the cursor or adds one
     * in the empty spot, and neither goes through the pick path the method above guards. Locked keys
     * would be deletable and lock blocks could be filled, so that branch is stopped at the head.
     */
    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedShortcut(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        UIKeyframes view = (UIKeyframes) (Object) this;

        if (context.mouseButton == 0 && view.graphArea.isInside(context)
            && (Window.isCtrlPressed() || view.isCreatingAtPlayhead() || view.isDuplicatingAtPlayhead())
            && KeyframeLockClient.blocksKeyframeEdit(view, context.mouseX, context.mouseY))
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

    /**
     * Select-all, select-track and the marquee reach locked keys without any click on them.
     * They all end in a selection refresh, so the selection is trimmed whenever the editor
     * changes state - after the call, so the locked keys never stay selected long enough for
     * the next drag or shortcut to pick them up.
     */
    @Inject(method = "cacheKeyframes", at = @At("RETURN"), remap = false)
    private void bbs_together$filterLockedCache(CallbackInfo ci)
    {
        KeyframeLockClient.filterLockedSelection((UIKeyframes) (Object) this);
    }

    @Inject(method = "submitKeyframes", at = @At("RETURN"), remap = false)
    private void bbs_together$filterLockedSubmit(CallbackInfo ci)
    {
        KeyframeLockClient.filterLockedSelection((UIKeyframes) (Object) this);
    }
}
