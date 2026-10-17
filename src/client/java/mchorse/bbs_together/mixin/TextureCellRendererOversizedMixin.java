package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.textures.TextureCellRenderer;
import mchorse.bbs_mod.ui.textures.TextureEntry;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_together.sync.OversizedAssets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks an over-size texture cell in the texture browser the same way a too-large model is
 * marked in the morph picker: dimmed cell plus a red "too large" tag.
 *
 * <p>The cell keeps the name BBS gives it, so the user can see *which* image is missing instead
 * of wondering where the file went — the file is there, only as a stub, because it exceeds
 * {@code asset_sync_max_mb}.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = TextureCellRenderer.class, remap = false)
public abstract class TextureCellRendererOversizedMixin
{
    private static final int TOP_RED = 0xCC_8B0000;
    private static final int BOT_RED = 0xCC_D02020;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void together$paintOversized(UIContext context, TextureEntry entry, int x, int y, int w, int h, CellState state, CallbackInfo ci)
    {
        if (OversizedAssets.isEmpty() || entry == null || entry.link() == null)
        {
            return;
        }

        Long size = OversizedAssets.sizeOf(entry.link().toString());

        if (size == null)
        {
            return;
        }

        Batcher2D batcher = context.batcher;
        FontRenderer font = batcher.getFont();

        batcher.gradientVBox(x, y, x + w, y + h, TOP_RED, BOT_RED);

        /* Cells are small: fit the size on the label only when it actually leaves room for it. */
        String label = font.getWidth(OversizedAssets.label(size.longValue())) <= w - 8
            ? OversizedAssets.label(size.longValue())
            : "文件过大";
        int labelX = x + (w - font.getWidth(label)) / 2;
        int labelY = y + (h - font.getHeight()) / 2;

        batcher.text(label, labelX, labelY, 0xFFFFFFFF, true);
    }
}
