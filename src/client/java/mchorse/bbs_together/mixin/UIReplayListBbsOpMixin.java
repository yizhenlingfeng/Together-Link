package mchorse.bbs_together.mixin;

import java.util.List;
import java.util.function.Consumer;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only (level 0) lock on the replay list: adding, duplicating, removing, pasting,
 * importing from camera/model-block, editing category, and opening the form editor are all
 * short-circuited. Selecting and browsing replays stays available. The server still refuses
 * the resulting packets, so this is just the client-side UX.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIReplayList.class, remap = false)
public class UIReplayListBbsOpMixin
{
    @Inject(method = "addReplay(Lorg/joml/Vector3d;FF)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockAdd(Vector3d pos, float yaw, float pitch, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "dupeReplay", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockDupe(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "removeReplay", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockRemove(CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "pasteReplay", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockPaste(MapType data, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "fromCamera", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFromCamera(int index, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "fromModelBlock(Lmchorse/bbs_mod/blocks/entities/ModelBlockEntity;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFromModelBlock(ModelBlockEntity be, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "applyReplayCategory", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockApplyCategory(List<Replay> replays, String category, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "removeReplayCategory", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockRemoveCategory(String category, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }

    @Inject(method = "openFormEditor", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFormEditor(ValueForm form, boolean expanded, Consumer<?> consumer, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit("film")) ci.cancel();
    }
}
