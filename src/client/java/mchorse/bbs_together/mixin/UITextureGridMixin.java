package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.textures.TextureEntry;
import mchorse.bbs_mod.ui.textures.UITextureGrid;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.sync.AssetSyncTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a 1px sync strip along the bottom of a texture grid cell while that texture asset
 * is being synced.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = {UITextureGrid.class}, remap = false)
public abstract class UITextureGridMixin {

    @Inject(method = {"renderCell"}, at = {@At(value = "TAIL")}, remap = false)
    private void bbstogether$renderSyncStrip(UIContext context, TextureEntry item, int x, int y, int w, int h, CellState state, CallbackInfo ci) {
        if (item == null || item.folder()) return;
        if (!(Boolean) TogetherSettings.assetSyncProgressBar.get()) return;
        Link link = item.link();
        if (link == null || !Link.isAssets(link)) return;

        AssetSyncTracker.AssetState st = AssetSyncTracker.get(link.path);
        if (st == null || st.state == AssetSyncTracker.State.PENDING) return;

        float bx1 = x;
        float bx2 = x + w;
        float by = y + h - 1F;
        int primary = 0xFF000000 | (BBSSettings.primaryColor.get() & 0xFFFFFF);

        if (st.state == AssetSyncTracker.State.ACTIVE) {
            context.batcher.box(bx1, by, bx2, by + 1F, 0x33FFFFFF);
            float cycle = (System.currentTimeMillis() % 1000L) / 1000F;
            float prog = cycle < 0.5F ? cycle * 2F : (1F - cycle) * 2F;
            float head = bx1 + (bx2 - bx1 - 6F) * prog;
            context.batcher.box(head, by, head + 6F, by + 1F, 0xFFFFFFFF);
        } else if (st.state == AssetSyncTracker.State.DONE) {
            context.batcher.box(bx1, by, bx2, by + 1F, primary);
        } else if (st.state == AssetSyncTracker.State.ERROR) {
            context.batcher.box(bx1, by, bx2, by + 1F, 0xFFFF5555);
        }
    }
}
