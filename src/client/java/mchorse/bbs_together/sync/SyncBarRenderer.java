package mchorse.bbs_together.sync;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.Direction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Draws the thin global asset-sync bar hugging the inner edge of the dashboard task bar.
 *
 * <p>Minimal by design: a 2px strip, no box, no text, fades out shortly after the run settles.
 * It follows whichever edge the task bar is docked to (bottom / top / left / right).</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncBarRenderer {

    private static final int THICK = 2;

    private SyncBarRenderer() {}

    public static void render(UIRenderingContext context, UIDashboard dashboard) {
        /* Master switch: off means the global bar never renders. */
        if (!(Boolean) TogetherSettings.assetSyncProgressBar.get()) return;

        boolean always = (Boolean) TogetherSettings.assetSyncProgressBarAlways.get();

        /* When "always show" is on, don't expire the finished state back to idle. */
        if (!always) {
            AssetSyncTracker.tick();
        }

        AssetSyncTracker.Phase phase = AssetSyncTracker.getPhase();
        /* When "always show" is on, keep the (empty) track visible even when idle, so toggling the
         * switch back on brings the bar back immediately instead of waiting for a new sync run. */
        if (phase == AssetSyncTracker.Phase.IDLE && !always) return;

        UIDashboardPanels panels = dashboard.getPanels();
        Area bar = panels.taskBar.area;
        Direction side = panels.getSide();
        int width = dashboard.width;
        int height = dashboard.height;

        /* Fade-out alpha once finished (skipped when "always show" is on). */
        int alpha = 0xFF;
        if (phase == AssetSyncTracker.Phase.FINISHED && !always) {
            long elapsed = System.currentTimeMillis() - AssetSyncTracker.getFinishedAt();
            float t = Math.min(1F, (float) elapsed / (float) AssetSyncTracker.HOLD_AFTER_DONE_MS);
            alpha = (int) (0xFF * (1F - t));
            if (alpha <= 4) return;
        }

        int primary = 0xFF000000 | (BBSSettings.primaryColor.get() & 0xFFFFFF);
        int track = withAlpha(0x22FFFFFF, alpha);
        int fill = withAlpha(primary, alpha);
        int shimmer = withAlpha(0xFFFFFFFF, alpha);

        boolean horizontal = side == Direction.BOTTOM || side == Direction.TOP;

        /* Track (full length along the docked edge). */
        if (horizontal) {
            int y = side == Direction.BOTTOM ? bar.y - THICK : bar.ey();
            context.batcher.box(0, y, width, y + THICK, track);
        } else {
            int x = side == Direction.LEFT ? bar.ex() : bar.x - THICK;
            context.batcher.box(x, 0, x + THICK, height, track);
        }

        if (phase == AssetSyncTracker.Phase.SCANNING) {
            /* Indeterminate shimmer sweeping back and forth. */
            long t = System.currentTimeMillis() % 2000L;
            float cycle = t < 1000L ? (t / 1000F) : (1F - (t - 1000L) / 1000F);
            float len = horizontal ? width : height;
            float seg = len * 0.25F;
            float pos = cycle * (len - seg);
            if (horizontal) {
                int y = side == Direction.BOTTOM ? bar.y - THICK : bar.ey();
                context.batcher.box(pos, y, pos + seg, y + THICK, shimmer);
            } else {
                int x = side == Direction.LEFT ? bar.ex() : bar.x - THICK;
                context.batcher.box(x, pos, x + THICK, pos + seg, shimmer);
            }
            return;
        }

        /* Determinate fill. */
        float p = AssetSyncTracker.globalProgress();
        if (horizontal) {
            int y = side == Direction.BOTTOM ? bar.y - THICK : bar.ey();
            float fillW = (width - 0F) * p;
            if (fillW > 0.5F) {
                context.batcher.box(0, y, fillW, y + THICK, fill);
            }
        } else {
            int x = side == Direction.LEFT ? bar.ex() : bar.x - THICK;
            float fillH = (height - 0F) * p;
            if (fillH > 0.5F) {
                context.batcher.box(x, 0, x + THICK, fillH, fill);
            }
        }

        /* Red tip when something failed. */
        if (AssetSyncTracker.hasErrors()) {
            int red = withAlpha(0xFFFF5555, alpha);
            if (horizontal) {
                int y = side == Direction.BOTTOM ? bar.y - THICK : bar.ey();
                context.batcher.box(width - 6F, y, width, y + THICK, red);
            } else {
                int x = side == Direction.LEFT ? bar.ex() : bar.x - THICK;
                context.batcher.box(x, height - 6F, x + THICK, height, red);
            }
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
