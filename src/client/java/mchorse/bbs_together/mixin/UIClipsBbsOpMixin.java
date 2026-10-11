package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.ReadOnlyOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-only (level 0) lock on the film timeline: clips can no longer be grabbed, dragged,
 * added, pasted, removed, shifted or selected — but clicking/dragging anywhere on the
 * timeline (ruler <em>and</em> clip rows) scrubs the playhead, so a level-0 player can
 * still preview the film in time. A slim lock band above the layers advertises why.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIClips.class, remap = false)
public class UIClipsBbsOpMixin
{
    @Shadow(remap = false)
    private IUIClipsDelegate delegate;

    @Shadow(remap = false)
    private boolean scrubbing;

    @Shadow(remap = false)
    private boolean isInRuler(int mouseY)
    {
        throw new AssertionError();
    }

    /**
     * Left clicks anywhere on the timeline body (ruler included, handled by the original flow)
     * scrub the playhead instead of picking/grabbing clips. We intercept at the subMouseClicked
     * entry so no internal branch of the original handler (selection, marquee, grab setup) can
     * run for clip rows; the ruler falls through and keeps its marker-priority scrub.
     */
    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$scrubInsteadOfGrab(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        UIClips self = (UIClips) (Object) this;

        if (!BbsOpClientState.isReadOnly() || context.mouseButton != 0)
        {
            return;
        }

        if (!self.area.isInside(context) || self.hasEmbeddedView() || this.isInRuler(context.mouseY))
        {
            return;
        }

        /* Leave the vertical scrollbar (right edge) alone so scrolling keeps working. */
        if (context.mouseX > self.area.ex() - 10)
        {
            return;
        }

        this.scrubbing = true;
        this.delegate.stopPlaybackOnScrub();
        this.delegate.setCursor(Math.max(0F, self.fromGraphCursor(context.mouseX)));

        cir.setReturnValue(true);
    }

    @Inject(method = "handleRightClick", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockRightClick(int mouseX, int mouseY, boolean ctrl, boolean shift, boolean alt, CallbackInfoReturnable<Boolean> cir)
    {
        if (BbsOpClientState.isReadOnly())
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addClip(Lmchorse/bbs_mod/resources/Link;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockAddLink(Link type, int tick, int layer, int duration, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "addClip(Lmchorse/bbs_mod/utils/clips/Clip;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockAddClip(Clip clip, int tick, int layer, int duration, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "pasteClips(Lmchorse/bbs_mod/data/types/MapType;II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPasteAtMouse(MapType data, int mouseX, int mouseY, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "pasteClips(Lmchorse/bbs_mod/data/types/MapType;I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPasteAtTick(MapType data, int tick, CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "removeSelected", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockRemove(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "shiftToCursor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockShift(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "shiftDurationToCursor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockShiftDuration(CallbackInfo ci)
    {
        if (BbsOpClientState.isReadOnly())
        {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$drawLockBand(UIContext context, CallbackInfo ci)
    {
        if (!BbsOpClientState.isReadOnly())
        {
            return;
        }

        Area a = ((UIClips) (Object) this).area;
        String label = ReadOnlyOverlay.label();

        context.batcher.box(a.x, a.y, a.ex(), a.y + 14, 0xB0181818);
        context.batcher.icon(Icons.LOCKED, a.x + 3, a.y + 2);
        context.batcher.text(label, a.x + 3 + Icons.LOCKED.w + 4, a.y + 4, Colors.WHITE);
    }
}
