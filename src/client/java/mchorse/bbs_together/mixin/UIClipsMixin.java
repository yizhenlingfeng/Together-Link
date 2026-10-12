package mchorse.bbs_together.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * UIClips collaboration cursor overlays (drawn after renderCameraWork) merged with the
 * read-only (level 0) lock: clips can no longer be grabbed, dragged, added, pasted,
 * removed, shifted or selected — but clicking/dragging anywhere on the timeline
 * (ruler <em>and</em> clip rows) scrubs the playhead, so a level-0 player can still
 * preview the film in time. A slim lock band above the layers advertises why.
 */
@Environment(value=EnvType.CLIENT)
@Mixin(value={UIClips.class}, remap=false)
public class UIClipsMixin {
    @Shadow(remap = false)
    private IUIClipsDelegate delegate;

    @Shadow(remap = false)
    private boolean scrubbing;

    @Shadow(remap = false)
    private boolean isInRuler(int mouseY)
    {
        throw new AssertionError();
    }

    @Inject(method={"render"}, at={@At(value="INVOKE", target="Lmchorse/bbs_mod/ui/film/UIClips;renderCameraWork(Lmchorse/bbs_mod/ui/framework/UIContext;)V", shift=At.Shift.AFTER)}, remap=false)
    private void onAfterRenderCameraWork(UIContext context, CallbackInfo ci) {
        if (TogetherClientNetwork.playerCursors.isEmpty()) {
            return;
        }
        UIClips self = (UIClips)(Object)this;
        if (self.getClips() == null) {
            return;
        }
        List allClips = self.getClips().get();
        context.batcher.clip(self.vertical.area, context);
        for (Map.Entry<UUID, TogetherClientNetwork.CursorPosition> entry : TogetherClientNetwork.playerCursors.entrySet()) {
            Integer col;
            TogetherClientNetwork.CursorPosition pos = entry.getValue();
            if (pos.editorType != 0 && pos.editorType != 2 || pos.selectedIndices.isEmpty() || (col = TogetherClientNetwork.playerColors.get(entry.getKey())) == null) continue;
            int outlineColor = col | 0xFF000000;
            for (int clipIndex : pos.selectedIndices) {
                if (clipIndex < 0 || clipIndex >= allClips.size()) continue;
                Clip clip = (Clip)allClips.get(clipIndex);
                int x = self.toGraphX(((Integer)clip.tick.get()).intValue());
                int y = self.toLayerY(((Integer)clip.layer.get()).intValue());
                int x2 = Math.max(self.toGraphX((Integer)clip.tick.get() + (Integer)clip.duration.get()), x + 2);
                int fillColor = Colors.setA((int)outlineColor, (float)0.35f);
                context.batcher.box((float)x, (float)y, (float)x2, (float)(y + 20), fillColor);
                context.batcher.outline((float)x, (float)y, (float)x2, (float)(y + 20), outlineColor);
            }
        }
        context.batcher.unclip(context);
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$drawLockBand(UIContext context, CallbackInfo ci)
    {
        if (BbsOpClientState.canEdit("film"))
        {
            return;
        }

        Area a = ((UIClips) (Object) this).area;
        String label = ReadOnlyOverlay.label();

        context.batcher.box(a.x, a.y, a.ex(), a.y + 14, 0xB0181818);
        context.batcher.icon(Icons.LOCKED, a.x + 3, a.y + 2);
        context.batcher.text(label, a.x + 3 + Icons.LOCKED.w + 4, a.y + 4, Colors.WHITE);
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

        if (BbsOpClientState.canEdit("film") || context.mouseButton != 0)
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
        if (!BbsOpClientState.canEdit("film"))
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "addClip(Lmchorse/bbs_mod/resources/Link;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockAddLink(Link type, int tick, int layer, int duration, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "addClip(Lmchorse/bbs_mod/utils/clips/Clip;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockAddClip(Clip clip, int tick, int layer, int duration, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "pasteClips(Lmchorse/bbs_mod/data/types/MapType;II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPasteAtMouse(MapType data, int mouseX, int mouseY, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "pasteClips(Lmchorse/bbs_mod/data/types/MapType;I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPasteAtTick(MapType data, int tick, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "removeSelected", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockRemove(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "shiftToCursor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockShift(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }

    @Inject(method = "shiftDurationToCursor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockShiftDuration(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film"))
        {
            ci.cancel();
        }
    }
}
