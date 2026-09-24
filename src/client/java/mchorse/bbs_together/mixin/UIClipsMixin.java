package mchorse.bbs_together.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIClips.class}, remap=false)
public class UIClipsMixin {
    private static final int EDITOR_CAMERA = 0;
    private static final int EDITOR_ACTION = 2;
    private static final int LAYER_HEIGHT = 20;

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
}

