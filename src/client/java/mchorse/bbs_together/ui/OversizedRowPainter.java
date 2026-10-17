package mchorse.bbs_together.ui;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_together.sync.OversizedAssets;

/**
 * Shared "this row is a file that is too big to sync" decoration for list rows.
 *
 * <p>BBS draws rows in several unrelated classes ({@code UIList} and the sound list override it
 * separately), so the drawing lives here once and each hook calls it. The row is dimmed with a
 * translucent scrim — the name underneath reads as greyed out — and tagged with
 * {@code 文件尺寸过大 · 706.6 MB} on the right, but only when the tag does not collide with the
 * name itself.</p>
 */
public final class OversizedRowPainter
{
    private static final int SCRIM = 0x99000000;
    private static final int TAG_COLOR = 0xFFFF9C9C;

    private OversizedRowPainter() {}

    /**
     * Paints the decoration when {@code element} refers to an over-size asset.
     *
     * @return {@code true} when something was painted.
     */
    public static boolean paint(UIContext context, Object element, int x, int y, int w, int h)
    {
        if (OversizedAssets.isEmpty() || w <= 0 || h <= 0)
        {
            return false;
        }

        String text;

        if (element instanceof Link link)
        {
            text = link.toString();
        }
        else if (element instanceof String string)
        {
            text = string;
        }
        else
        {
            return false;
        }

        Long size = OversizedAssets.sizeOf(text);

        if (size == null)
        {
            return false;
        }

        Batcher2D batcher = context.batcher;
        FontRenderer font = batcher.getFont();

        batcher.box(x, y, x + w, y + h, SCRIM);

        String label = OversizedAssets.label(size.longValue());
        int labelX = x + w - font.getWidth(label) - 4;
        int labelY = y + (h - font.getHeight()) / 2;

        if (labelX > x + w / 2)
        {
            batcher.textShadow(label, labelX, labelY, TAG_COLOR);
        }

        return true;
    }
}
