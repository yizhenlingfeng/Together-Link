package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.network.CoopClient;
import mchorse.bbs_together.network.CoopClient.Room;
import mchorse.bbs_together.network.CoopClient.RoomMember;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.UUID;

/**
 * The cooperative recording picking room: replays on the left (a claimed one carries its
 * claimer's avatar), members on the right (gray = no replay yet, green = claimed), everyone's
 * mouse position as a colored dot, and the host's start button.
 *
 * <p>The replay column scrolls and every row previews its form, like BBS' own replay list. A
 * minimal menu of its own, so the room opens over the world or the dashboard alike.</p>
 */
@Environment(EnvType.CLIENT)
public class UICoopRoomPanel extends UIOverlayPanel
{
    private static final int BOX_W = 190;
    private static final int BOX_H = 34;
    private static final int GAP = 26;

    /* Row pitch, the scrollbar strip the rows leave free, and the model preview square. */
    private static final int PITCH = BOX_H + GAP;
    private static final int SCROLLBAR = 8;
    private static final int PREVIEW = 30;

    /* Space above the first row so its claimer's avatar (which sits on the top edge) isn't clipped. */
    private static final int PAD = 10;

    private final RoomView view;
    private final UIButton start;

    public UICoopRoomPanel()
    {
        super(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.room.title"));

        this.view = new RoomView();

        this.view.relative(this.content).xy(0, 0).w(1F).h(1F, -26);

        this.start = new UIButton(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.room.start"), (b) ->
        {
            Room room = CoopClient.room;

            if (room != null)
            {
                CoopClient.sendStart(room.id);
            }
        });

        UIButton leave = new UIButton(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.room.leave"), (b) ->
        {
            Room room = CoopClient.room;

            if (room != null)
            {
                CoopClient.sendLeave(room.id);
            }

            CoopClient.room = null;
            CoopClient.closeRoomScreen();
        });

        UIElement buttons = mchorse.bbs_mod.ui.utils.UI.row(4, this.start, leave);

        buttons.relative(this.content).x(0).y(1F, -24).w(1F).h(22);

        this.content.add(this.view, buttons);
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        Room room = CoopClient.room;
        UUID self = playerId();

        if (room == null || self == null)
        {
            return;
        }

        boolean host = self.equals(room.host);
        boolean allPicked = !room.members.isEmpty();

        for (RoomMember member : room.members)
        {
            if (member.pick < 0)
            {
                allPicked = false;

                break;
            }
        }

        this.start.setVisible(host);
        this.start.setEnabled(host && allPicked && !room.isRecording());
    }

    private static UUID playerId()
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        return mc.player == null ? null : mc.player.getUuid();
    }

    private static UUID claimerOf(Room room, int index)
    {
        for (RoomMember member : room.members)
        {
            if (member.pick == index)
            {
                return member.uuid;
            }
        }

        return null;
    }

    private class RoomView extends UIElement
    {
        private final CoopReplayList replays;

        public RoomView()
        {
            this.replays = new CoopReplayList();

            this.replays.relative(this).xy(10, 4).w(BOX_W + SCROLLBAR).h(1F, -12);

            this.add(this.replays);
        }

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            Room room = CoopClient.room;

            if (room == null)
            {
                return;
            }

            UUID self = playerId();

            if (self == null)
            {
                return;
            }

            /* Own mouse position out (throttled), others' in as dots. */
            int screenW = context.menu.width;
            int screenH = context.menu.height;

            CoopClient.sendMouseThrottled(room.id,
                (float) context.mouseX / Math.max(1, screenW),
                (float) context.mouseY / Math.max(1, screenH));

            /* Right column: members with gray/green outlines */
            int rx = this.area.ex() - 160;
            int ry = this.area.y + 6;

            for (RoomMember member : room.members)
            {
                boolean picked = member.pick >= 0;
                int outline = picked ? 0xFF2FBF2F : Colors.GRAY;

                context.batcher.box(rx - 1, ry - 1, rx + 17, ry + 17, outline);
                UIPlayerFaceRenderer.draw(context.batcher, member.name, member.uuid, rx, ry, 16);

                String label = member.name + (member.uuid.equals(room.host) ? " \u2605" : "");

                context.batcher.text(label, rx + 22, ry + 4, picked ? Colors.WHITE : Colors.GRAY, false);

                ry += 22;
            }

            /* Everyone else's mouse position as a player-colored dot */
            for (RoomMember member : room.members)
            {
                if (member.uuid.equals(self))
                {
                    continue;
                }

                float[] pos = room.mice.get(member.uuid);

                if (pos == null)
                {
                    continue;
                }

                Integer color = TogetherClientNetwork.playerColors.get(member.uuid);
                int dot = color == null ? 0xFFFFFFFF : (0xFF000000 | color);
                int dx = this.area.x + (int) (pos[0] * this.area.w);
                int dy = this.area.y + (int) (pos[1] * this.area.h);

                context.batcher.box(dx - 2, dy - 2, dx + 2, dy + 2, dot);
                context.batcher.box(dx - 1, dy - 1, dx + 1, dy + 1, 0xFFFFFFFF);
            }
        }
    }

    /**
     * The scrolling replay column. Rows are child elements so the scroll view owns the wheel,
     * the drag and the scrollbar; each row reads the room state live, so a pick update only
     * needs a repaint.
     */
    private class CoopReplayList extends UIScrollView
    {
        /* How many rows are currently built; the column only rebuilds when that changes. */
        private int built;

        /* The film the previews come from, resolved once per session and retried when the
         * invitee hasn't received it yet (an invitee without the file has no local copy). */
        private String filmId;
        private Film film;
        private long filmRetryAt;

        public CoopReplayList()
        {
            this.scroll.scrollItemSize = PITCH;
            this.scroll.scrollSpeed = 24;
        }

        @Override
        public void render(UIContext context)
        {
            this.refresh();

            super.render(context);
        }

        private void refresh()
        {
            Room room = CoopClient.room;
            String id = room == null ? null : room.filmId;

            if (id == null)
            {
                this.filmId = null;
                this.film = null;
            }
            else if (!id.equals(this.filmId))
            {
                this.filmId = id;
                this.film = null;
                this.filmRetryAt = 0L;
            }

            if (this.film == null && id != null)
            {
                long now = System.currentTimeMillis();

                if (now >= this.filmRetryAt)
                {
                    this.film = CoopClient.previewFilm(id);
                    this.filmRetryAt = now + 2000L;
                }
            }

            int count = room == null ? 0 : room.replayLabels.size();

            if (this.built != count)
            {
                this.rebuild(count);
            }

            /* PAD keeps the first row's avatar inside the view; no trailing gap after the last. */
            this.scroll.scrollSize = count == 0 ? 0 : PAD + (count - 1) * PITCH + BOX_H;

            this.scroll.clamp();
        }

        private void rebuild(int count)
        {
            this.removeAll();
            this.built = count;

            for (int i = 0; i < count; i++)
            {
                ReplayBox box = new ReplayBox(this, i);

                box.relative(this).x(0).y(PAD + i * PITCH).w(1F, -SCROLLBAR).h(BOX_H);

                this.add(box);
            }

            /* Lay the fresh rows out now instead of showing one empty frame. */
            this.resize();
        }
    }

    /** One replay: its frame, its claimer's avatar, its label and its form's model preview. */
    private class ReplayBox extends UIElement
    {
        private final CoopReplayList list;
        private final int index;

        /* A model that throws while previewing is skipped from then on, see renderPreview. */
        private boolean previewFailed;

        public ReplayBox(CoopReplayList list, int index)
        {
            this.list = list;
            this.index = index;
        }

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            Room room = CoopClient.room;

            if (room == null || this.index >= room.replayLabels.size())
            {
                return;
            }

            UUID self = playerId();
            UUID claimer = claimerOf(room, this.index);
            boolean mine = self != null && self.equals(claimer);
            int x = this.area.x;
            int y = this.area.y;
            int ex = this.area.ex();
            int ey = this.area.ey();

            if (claimer != null)
            {
                /* Claimed: green frame + the claimer's avatar sitting on the top edge. */
                context.batcher.box(x, y, ex, y + 1, 0xFF2FBF2F);
                context.batcher.box(x, ey - 1, ex, ey, 0xFF2FBF2F);
                context.batcher.box(x, y, x + 1, ey, 0xFF2FBF2F);
                context.batcher.box(ex - 1, y, ex, ey, 0xFF2FBF2F);

                for (RoomMember member : room.members)
                {
                    if (member.pick == this.index)
                    {
                        int ax = x + 4;
                        int ay = y - 8;

                        context.batcher.box(ax - 1, ay - 1, ax + 15, ay + 15, 0xFF2FBF2F);
                        UIPlayerFaceRenderer.draw(context.batcher, member.name, member.uuid, ax, ay, 14);
                    }
                }
            }
            else
            {
                int primary = 0xFF000000 | BBSSettings.primaryColor.get();

                context.batcher.box(x, y, ex, y + 1, primary);
                context.batcher.box(x, ey - 1, ex, ey, primary);
                context.batcher.box(x, y, x + 1, ey, primary);
                context.batcher.box(ex - 1, y, ex, ey, primary);
            }

            String label = room.replayLabels.get(this.index);
            String name = label.isEmpty()
                ? mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.room.replay").get().replace("{index}", String.valueOf(this.index + 1))
                : label;
            int previewX = ex - PREVIEW - 2;
            int textWidth = Math.max(16, previewX - (x + 6) - 4);

            name = context.batcher.getFont().limitToWidth(name, textWidth);

            context.batcher.text(name, x + 6, y + (BOX_H - context.batcher.getFont().getHeight()) / 2 + 1,
                mine ? 0xFF7CFC7C : Colors.LIGHTEST_GRAY, false);

            this.renderPreview(context, previewX, y + (BOX_H - PREVIEW) / 2);
        }

        private void renderPreview(UIContext context, int px, int py)
        {
            if (!BBSSettings.listModelPreview.get())
            {
                return;
            }

            Form form = this.form();

            if (form == null || this.previewFailed)
            {
                return;
            }

            /* The scissor is a stack: this pair is balanced and the unclip restores the column's
             * clip. Never re-open it by hand - an unpopped clip outlives the menu and blacks out
             * the game until it restarts. */
            context.batcher.clip(px, py, PREVIEW, PREVIEW, context);

            try
            {
                FormUtilsClient.renderPreview(form, context, px, py, px + PREVIEW, py + PREVIEW);
            }
            catch (Throwable throwable)
            {
                /* Form renderers are third party code: an exception escaping here would skip
                 * the scroll view's unclip and leave the scissor on. */
                this.previewFailed = true;

                System.out.println("[BBSTogether] Replay " + (this.index + 1) + " preview failed: " + throwable);
            }
            finally
            {
                context.batcher.unclip(context);
            }
        }

        private Form form()
        {
            Film film = this.list.film;

            if (film == null)
            {
                return null;
            }

            List<Replay> replays = film.replays.getList();

            if (this.index >= replays.size())
            {
                return null;
            }

            return replays.get(this.index).form.get();
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            Room room = CoopClient.room;
            UUID self = playerId();

            if (room == null || self == null || room.isRecording() || !this.area.isInside(context))
            {
                return false;
            }

            UUID claimer = claimerOf(room, this.index);

            if (context.mouseButton == 0)
            {
                if (claimer == null)
                {
                    /* Claim a free replay. */
                    CoopClient.sendPick(room.id, this.index);
                }
                /* Clicking an own/other's claimed box does nothing — releasing
                 * goes through the right-click below. */

                return true;
            }

            if (context.mouseButton == 1 && self.equals(claimer))
            {
                /* Right-click own avatar (the claimed box) to release the replay. */
                CoopClient.sendPick(room.id, -1);

                return true;
            }

            return false;
        }
    }
}
