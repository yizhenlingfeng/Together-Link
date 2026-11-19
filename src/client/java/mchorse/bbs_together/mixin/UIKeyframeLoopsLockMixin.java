package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeLoops;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.locks.KeyframeLockClient;
import mchorse.bbs_together.locks.KeyframeLockData;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A loop block that intersects a keyframe lock belongs to that lock: it cannot be
 * selected, dragged, resized or deleted, otherwise the whole loop could be moved and
 * indirectly change the locked keys.
 */
@Mixin(value = UIKeyframeLoops.class, remap = false)
public abstract class UIKeyframeLoopsLockMixin
{
    @Shadow
    private UIKeyframes view;

    @Shadow
    private KeyframeChannel<?> selectedChannel;

    @Shadow
    private String selectedId;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedLoopMouse(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (context.mouseButton != 0 || this.view == null || this.view.getGraph() == null)
        {
            return;
        }

        UIKeyframeSheet sheet = this.view.getGraph().getSheet(context.mouseY);

        if (sheet == null || KeyframeLockData.ownerOf(sheet.channel) == null)
        {
            return;
        }

        int[] row = KeyframeLockClient.row(this.view, sheet);

        if (context.mouseY >= row[0] && context.mouseY < row[0] + row[1] && KeyframeLockClient.loopLocked(this.view, sheet, context.mouseX))
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedLoopDelete(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (this.selectedId == null || this.selectedChannel == null || !context.isPressed(GLFW.GLFW_KEY_DELETE))
        {
            return;
        }

        KeyframeLoop loop = this.selectedChannel.getLoop(this.selectedId);

        if (loop == null)
        {
            return;
        }

        if (KeyframeLockData.loopLocked(KeyframeLockData.ownerOf(this.selectedChannel), this.selectedChannel.getId(), loop, this.selectedChannel.getLoopEnd(loop)))
        {
            cir.setReturnValue(true);
        }
    }
}
