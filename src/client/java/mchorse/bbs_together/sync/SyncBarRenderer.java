package mchorse.bbs_together.sync;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.TogetherSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Minimal bottom-left sync indicator.
 *
 * <p>Active: a compact pill with an icon, a 2px progress line and a percentage.
 * Finished: a short checkmark pulse that fades away. Idle (only with "always show"):
 * a tiny checkmark dot. Drawn at the end of {@code UIContext.postRender} so it stays above
 * task bars, menus and overlays; it is never a UIElement, so clicks pass through.</p>
 */
@Environment(EnvType.CLIENT)
public final class SyncBarRenderer {

    private static final int ACTIVE_W = 142;
    private static final int ACTIVE_H = 24;
    private static final int DONE_W = 154;
    private static final int DONE_H = 26;
    private static final int DOT = 20;
    private static final int MARGIN = 14;
    private static final int PAD = 6;
    private static final int TRACK_H = 2;

    private static AssetSyncTracker.Phase lastPhase = AssetSyncTracker.Phase.IDLE;
    private static long phaseAt;
    private static long finishAt;
    private static long lastFrameAt;
    private static float shownProgress;

    private SyncBarRenderer() {}

    public static void render(UIContext context, UIDashboard dashboard) {
        if (!(Boolean) TogetherSettings.assetSyncProgressBar.get()) return;
        if (!dashboard.main.isVisible()) return;

        boolean always = (Boolean) TogetherSettings.assetSyncProgressBarAlways.get();
        long now = System.currentTimeMillis();
        AssetSyncTracker.tick();

        AssetSyncTracker.Phase phase = AssetSyncTracker.getPhase();

        if (phase != lastPhase) {
            lastPhase = phase;
            phaseAt = now;
            finishAt = phase == AssetSyncTracker.Phase.FINISHED ? now : 0L;

            if (phase == AssetSyncTracker.Phase.IDLE) {
                shownProgress = 0F;
            }
        }

        if (phase == AssetSyncTracker.Phase.IDLE && !always) {
            shownProgress = 0F;
            lastFrameAt = now;
            return;
        }

        if (phase == AssetSyncTracker.Phase.IDLE) {
            drawIdleDot(context, dashboard);
            lastFrameAt = now;
            return;
        }

        float dt = lastFrameAt == 0L ? 0.016F : Math.min(0.1F, (now - lastFrameAt) / 1000F);
        lastFrameAt = now;
        float target = phase == AssetSyncTracker.Phase.FINISHED ? 1F : AssetSyncTracker.globalProgress();
        float speed = 1F - (float) Math.exp(-dt * 12F);
        shownProgress += (target - shownProgress) * speed;

        drawPill(context, dashboard, phase, now, always);
    }

    private static void drawPill(UIContext context, UIDashboard dashboard,
                                 AssetSyncTracker.Phase phase, long now, boolean always) {
        boolean done = phase == AssetSyncTracker.Phase.FINISHED;
        int width = done ? DONE_W : ACTIVE_W;
        int height = done ? DONE_H : ACTIVE_H;
        int x = bottomLeftX(dashboard);
        int y = bottomLeftY(dashboard, height);

        float appear = clamp((now - phaseAt) / 180F);
        float alpha = done ? doneAlpha(now) : appear;
        int a = (int) (0xFF * clamp(alpha));

        if (a <= 2) {
            if (always && done) {
                drawIdleDot(context, dashboard);
            }

            return;
        }

        int primary = 0xFF000000 | (BBSSettings.primaryColor.get() & 0xFFFFFF);
        int background = withAlpha(Colors.mulRGB(primary | Colors.A100, 0.10F), a);
        int outline = withAlpha(primary, a);
        int white = withAlpha(0xFFFFFF, a);
        int muted = withAlpha(0xB0FFFFFF, a);
        int track = withAlpha(0x33FFFFFF, a);
        int error = withAlpha(0xFFFF5555, a);

        int drawX = x - (int) ((1F - appear) * 6F);

        /* Completion pulse: two expanding outlines instead of text. */
        if (done) {
            long elapsed = now - finishAt;
            float pulse = clamp(elapsed / 650F);

            if (pulse < 1F) {
                int grow = (int) (pulse * 7F);
                int pulseAlpha = (int) (a * (1F - pulse) * 0.7F);
                context.batcher.outline(drawX - grow, y - grow, drawX + width + grow, y + height + grow,
                    withAlpha(primary, pulseAlpha));

                if (pulse > 0.25F) {
                    int grow2 = (int) ((pulse - 0.25F) * 6F);
                    context.batcher.outline(drawX - grow2, y - grow2, drawX + width + grow2, y + height + grow2,
                        withAlpha(primary, (int) (a * (1F - pulse) * 0.35F)));
                }
            }
        }

        context.batcher.dropShadow(drawX + 2, y + 2, drawX + width - 2, y + height - 2, 6,
            withAlpha(0x000000, a / 4), outline);
        context.batcher.box(drawX + 1, y + 1, drawX + width - 1, y + height - 1, background);
        context.batcher.outline(drawX + 1, y + 1, drawX + width - 1, y + height - 1, outline);

        Icon icon = done
            ? Icons.CHECKMARK
            : phase == AssetSyncTracker.Phase.SCANNING
                ? Icons.REFRESH
                : Icons.DOWNLOAD;

        context.batcher.icon(icon, white, drawX + PAD, y + (height - 16) / 2);

        if (done) {
            int trackX = drawX + PAD + 18;
            int trackW = width - PAD - 18 - PAD;
            int trackY = y + (height - TRACK_H) / 2;
            context.batcher.box(trackX, trackY, trackX + trackW, trackY + TRACK_H, track);
            context.batcher.box(trackX, trackY, trackX + trackW, trackY + TRACK_H, outline);
            return;
        }

        FontRenderer font = context.batcher.getFont();
        String percent = phase == AssetSyncTracker.Phase.SCANNING
            ? ""
            : Math.round(shownProgress * 100F) + "%";
        int percentW = percent.isEmpty() ? 0 : font.getWidth(percent);
        int trackX = drawX + PAD + 18;
        int trackW = width - PAD - 18 - PAD - (percentW == 0 ? 0 : percentW + 6);
        int trackY = y + (height - TRACK_H) / 2;

        context.batcher.box(trackX, trackY, trackX + trackW, trackY + TRACK_H, track);

        if (phase == AssetSyncTracker.Phase.SCANNING) {
            long t = now % 1200L;
            float cycle = t < 600L ? (t / 600F) : (1F - (t - 600L) / 600F);
            float seg = trackW * 0.28F;
            float pos = trackX + cycle * (trackW - seg);
            context.batcher.box(pos, trackY, pos + seg, trackY + TRACK_H, white);
        } else {
            float fill = trackW * shownProgress;

            if (fill > 0.5F) {
                context.batcher.box(trackX, trackY, trackX + fill, trackY + TRACK_H, outline);
            }

            if (AssetSyncTracker.hasErrors()) {
                context.batcher.box(trackX + trackW - 4, y + PAD, trackX + trackW, y + PAD + 4, error);
            }
        }

        if (percentW > 0) {
            context.batcher.textShadow(percent, drawX + width - PAD - percentW,
                y + (height - font.getHeight()) / 2, muted);
        }
    }

    private static void drawIdleDot(UIContext context, UIDashboard dashboard) {
        int x = bottomLeftX(dashboard);
        int y = bottomLeftY(dashboard, DOT);
        int primary = 0xFF000000 | (BBSSettings.primaryColor.get() & 0xFFFFFF);
        int a = 0xB0;
        int background = withAlpha(Colors.mulRGB(primary | Colors.A100, 0.10F), a);

        context.batcher.box(x, y, x + DOT, y + DOT, background);
        context.batcher.outline(x, y, x + DOT, y + DOT, withAlpha(primary, a));
        context.batcher.icon(Icons.CHECKMARK, withAlpha(0xFFFFFF, a), x + 2, y + 2);
    }

    private static int bottomLeftX(UIDashboard dashboard) {
        UIDashboardPanels panels = dashboard.getPanels();

        return panels.getSide() == Direction.LEFT ? panels.taskBar.area.ex() + 10 : MARGIN;
    }

    private static int bottomLeftY(UIDashboard dashboard, int height) {
        UIDashboardPanels panels = dashboard.getPanels();
        Area bar = panels.taskBar.area;
        Direction side = panels.getSide();

        if (side == Direction.BOTTOM) {
            return Math.max(MARGIN, bar.y - height - 10);
        }

        return Math.max(MARGIN, dashboard.height - height - MARGIN);
    }

    private static float doneAlpha(long now) {
        long elapsed = now - finishAt;
        long fadeStart = AssetSyncTracker.HOLD_AFTER_DONE_MS - 420L;

        if (elapsed <= fadeStart) return 1F;

        return 1F - clamp((elapsed - fadeStart) / 420F);
    }

    private static float clamp(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
