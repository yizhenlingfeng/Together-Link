package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
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
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_together.network.CoopClient;
import mchorse.bbs_together.network.CoopClient.Room;
import mchorse.bbs_together.network.CoopClient.RoomMember;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;
import java.util.UUID;

/**
 * The cooperative recording room: film and phase in the header, the replay column on the left
 * (each row previews its model and carries its claimer), the participant column on the right, and
 * the status line with the host's start button at the bottom.
 *
 * <p>While picking, the rows are the working surface: left click claims a free replay, right click
 * releases your own. Once recording starts the room becomes a read-only progress board, which the
 * members who already handed their take in keep watching while the rest are still out recording.</p>
 */
@Environment(EnvType.CLIENT)
public class UICoopRoomPanel extends UIOverlayPanel
{
    /* Everything inside the panel is laid out in BBS UI units, so the whole room grows with the
     * UI scale instead of the fonts alone getting bigger. */
    private static final int MARGIN = 6;
    private static final int HEADER_H = 30;
    private static final int BODY_TOP = 38;
    private static final int BODY_BOTTOM = 32;
    private static final int MEMBERS_W = 176;

    private static final int ROW_H = 46;
    private static final int ROW_PITCH = 50;
    private static final int PREVIEW = 38;
    private static final int SCROLLBAR = 8;
    private static final int PAD = 2;

    private static final int BUTTON_W = 96;
    private static final int BUTTONS_W = 200;

    private static final int GREEN = 0xFF2FBF2F;
    private static final int LIGHT_GREEN = 0xFF7CFC7C;

    private final HeaderView header;
    private final ReplayList replays;
    private final MemberList members;
    private final UIButton start;
    private final UIButton close;

    public UICoopRoomPanel()
    {
        super(CoopL10n.key("bbs_together.coop.room.title"));

        this.header = new HeaderView();
        this.replays = new ReplayList();
        this.members = new MemberList();

        this.header.relative(this.content).x(MARGIN).y(4).w(1F, -MARGIN * 2).h(HEADER_H);
        this.replays.relative(this.content).x(MARGIN).y(BODY_TOP).w(1F, -(MEMBERS_W + MARGIN * 3)).h(1F, -(BODY_TOP + BODY_BOTTOM));
        this.members.relative(this.content).x(1F, -(MEMBERS_W + MARGIN)).y(BODY_TOP).w(MEMBERS_W).h(1F, -(BODY_TOP + BODY_BOTTOM));

        this.start = new UIButton(CoopL10n.key("bbs_together.coop.room.start"), (b) ->
        {
            Room room = CoopClient.room;

            if (room != null)
            {
                CoopClient.sendStart(room.id);
            }
        });

        this.close = new UIButton(CoopL10n.key("bbs_together.coop.room.close"), (b) -> CoopClient.dismissRoom());

        this.start.relative(this.content).x(1F, -(BUTTONS_W + MARGIN)).y(1F, -24).w(BUTTON_W).h(18);
        this.close.relative(this.content).x(1F, -(BUTTON_W + MARGIN)).y(1F, -24).w(BUTTON_W).h(18);

        this.content.add(this.header, this.replays, this.members, this.start, this.close);
    }

    @Override
    public void render(UIContext context)
    {
        this.syncButtons();

        super.render(context);

        Room room = CoopClient.room;

        if (room == null)
        {
            return;
        }

        this.renderStatus(context, room);
        this.renderMice(context, room);
    }

    /**
     * The start button belongs to the host and only lights up once everyone has a replay. While
     * recording the room is read-only, so the button goes away and the title says what it became.
     */
    private void syncButtons()
    {
        Room room = CoopClient.room;
        UUID self = CoopClient.playerId();
        boolean host = room != null && self != null && self.equals(room.host);
        boolean recording = room != null && room.isRecording();

        this.start.setVisible(host && !recording);
        this.start.setEnabled(host && allPicked(room) && !recording);

        this.title.label = CoopL10n.key(recording
            ? "bbs_together.coop.room.title_recording"
            : "bbs_together.coop.room.title");
    }

    private void renderStatus(UIContext context, Room room)
    {
        int x = this.content.area.x + MARGIN;
        int y = this.content.area.ey() - 19;
        int width = this.content.area.w - MARGIN * 2 - BUTTONS_W - 12;
        String status = statusLine(room);
        int color = room.isRecording() ? LIGHT_GREEN : Colors.LIGHTEST_GRAY;

        context.batcher.text(context.batcher.getFont().limitToWidth(status, Math.max(40, width)), x, y, color, false);
    }

    /** Everyone else's cursor, mapped into the panel: where in the room the others are pointing. */
    private void renderMice(UIContext context, Room room)
    {
        UUID self = CoopClient.playerId();

        if (self == null)
        {
            return;
        }

        int screenW = Math.max(1, context.menu.width);
        int screenH = Math.max(1, context.menu.height);

        CoopClient.sendMouseThrottled(room.id, (float) context.mouseX / screenW, (float) context.mouseY / screenH);

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
            int dx = this.content.area.x + (int) (pos[0] * this.content.area.w);
            int dy = this.content.area.y + (int) (pos[1] * this.content.area.h);

            context.batcher.box(dx - 2, dy - 2, dx + 2, dy + 2, dot);
            context.batcher.box(dx - 1, dy - 1, dx + 1, dy + 1, 0xFFFFFFFF);
        }
    }

    /* =====================================================================================
     *  Shared state helpers
     * ===================================================================================== */

    /** The member (submitted or still recording) that claimed this replay index. */
    private static RoomMember claimerOf(Room room, int index)
    {
        for (RoomMember member : room.members)
        {
            if (member.pick == index)
            {
                return member;
            }
        }

        for (RoomMember member : room.submitted)
        {
            if (member.pick == index)
            {
                return member;
            }
        }

        return null;
    }

    private static boolean allPicked(Room room)
    {
        if (room == null || room.members.isEmpty())
        {
            return false;
        }

        for (RoomMember member : room.members)
        {
            if (member.pick < 0)
            {
                return false;
            }
        }

        return true;
    }

    private static String text(String key, String... pairs)
    {
        String value = CoopL10n.key(key).get();

        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            value = value.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }

        return value;
    }

    /** Total replays plus who is still missing, phrased for the footer. */
    private static String statusLine(Room room)
    {
        int total = room.members.size() + room.submitted.size();

        if (room.isRecording())
        {
            String missing = namesOf(room.members);

            if (room.members.isEmpty())
            {
                return text("bbs_together.coop.room.status_finishing");
            }

            return text("bbs_together.coop.room.status_recording",
                "done", String.valueOf(room.submitted.size()),
                "total", String.valueOf(total),
                "time", TimeUtils.formatTime(room.recordingElapsedMs / 50D),
                "names", missing);
        }

        int picked = 0;

        for (RoomMember member : room.members)
        {
            if (member.pick >= 0)
            {
                picked++;
            }
        }

        if (!room.members.isEmpty() && picked == room.members.size())
        {
            return text("bbs_together.coop.room.status_ready");
        }

        return text("bbs_together.coop.room.status_picking",
            "picked", String.valueOf(picked),
            "total", String.valueOf(Math.max(1, room.members.size())));
    }

    private static String namesOf(List<RoomMember> members)
    {
        StringBuilder builder = new StringBuilder();

        for (RoomMember member : members)
        {
            if (builder.length() > 0)
            {
                builder.append(", ");
            }

            builder.append(member.name);
        }

        return builder.toString();
    }

    /* =====================================================================================
     *  Header: film, branch, phase, totals
     * ===================================================================================== */

    private class HeaderView extends UIElement
    {
        @Override
        public void render(UIContext context)
        {
            super.render(context);

            Room room = CoopClient.room;

            if (room == null)
            {
                return;
            }

            int x = this.area.x;
            int y = this.area.y;
            int fontH = context.batcher.getFont().getHeight();

            String phase = text(room.isRecording()
                ? "bbs_together.coop.room.phase_recording"
                : "bbs_together.coop.room.phase_picking");
            int badgeW = context.batcher.getFont().getWidth(phase) + 8;
            int badgeColor = room.isRecording() ? GREEN : (0xFF000000 | BBSSettings.primaryColor.get());

            /* Phase badge, right aligned, so it never fights the film id for room. */
            context.batcher.box(this.area.ex() - badgeW, y + 1, this.area.ex(), y + fontH + 4, badgeColor);
            context.batcher.text(phase, this.area.ex() - badgeW + 4, y + 3, Colors.WHITE, false);

            String film = room.filmId == null || room.filmId.isEmpty() ? "-" : room.filmId;
            int filmWidth = Math.max(24, this.area.w - badgeW - 8);

            context.batcher.text(context.batcher.getFont().limitToWidth(film, filmWidth), x, y + 3, Colors.WHITE, true);

            /* Second line: branch on the left, replay count and total duration on the right. */
            String branch = room.branch == null || room.branch.isEmpty()
                ? text("bbs_together.coop.room.branch_none")
                : text("bbs_together.coop.room.branch", "branch", room.branch);
            String counts = text("bbs_together.coop.room.replays", "count", String.valueOf(room.replayLabels.size()));
            int countsW = context.batcher.getFont().getWidth(counts);

            context.batcher.text(counts, this.area.ex() - countsW, y + fontH + 6, Colors.GRAY, false);
            context.batcher.text(context.batcher.getFont().limitToWidth(branch, Math.max(24, this.area.w - countsW - 8)),
                x, y + fontH + 6, Colors.LIGHTER_GRAY, false);
        }
    }

    /* =====================================================================================
     *  Replay column
     * ===================================================================================== */

    /**
     * The scrolling replay column. Rows are child elements so the scroll view owns the wheel, the
     * drag and the scrollbar, and each row reads the room state live.
     */
    private class ReplayList extends UIScrollView
    {
        /* How many rows are currently built; the column only rebuilds when that changes. */
        private int built;

        /* The film the previews and durations come from, retried while an invitee has no copy yet. */
        private String filmId;
        private Film film;
        private long filmRetryAt;

        public ReplayList()
        {
            this.scroll.scrollItemSize = ROW_PITCH;
            this.scroll.scrollSpeed = 24;
        }

        @Override
        public void render(UIContext context)
        {
            this.refresh();

            super.render(context);

            Room room = CoopClient.room;

            if (room != null && room.replayLabels.isEmpty())
            {
                context.batcher.text(text("bbs_together.coop.room.empty"), this.area.x + 6, this.area.y + 6, Colors.GRAY, false);
            }
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

            this.scroll.scrollSize = count == 0 ? 0 : PAD + (count - 1) * ROW_PITCH + ROW_H;

            this.scroll.clamp();
        }

        private void rebuild(int count)
        {
            this.removeAll();
            this.built = count;

            for (int i = 0; i < count; i++)
            {
                ReplayRow row = new ReplayRow(this, i);

                row.relative(this).x(0).y(PAD + i * ROW_PITCH).w(1F, -SCROLLBAR).h(ROW_H);

                this.add(row);
            }

            /* Lay the fresh rows out now instead of showing one empty frame. */
            this.resize();
        }

        private Replay replay(int index)
        {
            Film current = this.film;

            if (current == null)
            {
                return null;
            }

            List<Replay> replays = current.replays.getList();

            return index >= 0 && index < replays.size() ? replays.get(index) : null;
        }
    }

    /** One replay: its model preview, its label, its length and keyframe count, and its claimer. */
    private class ReplayRow extends UIElement
    {
        private final ReplayList list;
        private final int index;

        /* A model that throws while previewing is skipped from then on, see renderPreview. */
        private boolean previewFailed;

        public ReplayRow(ReplayList list, int index)
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

            UUID self = CoopClient.playerId();
            RoomMember claimer = claimerOf(room, this.index);
            boolean mine = self != null && claimer != null && self.equals(claimer.uuid);
            Replay replay = this.list.replay(this.index);

            int x = this.area.x;
            int y = this.area.y;
            int ex = this.area.ex();
            int ey = this.area.ey();

            context.batcher.box(x, y, ex, ey, 0x44000000);

            int frame = claimer != null
                ? (mine ? LIGHT_GREEN : GREEN)
                : (0xFF000000 | BBSSettings.primaryColor.get());

            context.batcher.box(x, y, ex, y + 1, frame);
            context.batcher.box(x, ey - 1, ex, ey, frame);
            context.batcher.box(x, y, x + 1, ey, frame);
            context.batcher.box(ex - 1, y, ex, ey, frame);

            this.renderPreview(context, x + 4, y + (ROW_H - PREVIEW) / 2);

            int fontH = context.batcher.getFont().getHeight();
            int textX = x + PREVIEW + 12;
            int textW = Math.max(24, ex - textX - 6 - (claimer == null ? 0 : 92));

            String label = room.replayLabels.get(this.index);

            if (label == null || label.isEmpty())
            {
                label = text("bbs_together.coop.room.replay", "index", String.valueOf(this.index + 1));
            }

            label = context.batcher.getFont().limitToWidth(label, textW);

            context.batcher.text(label, textX, y + 8, mine ? LIGHT_GREEN : Colors.WHITE, false);

            String meta = this.meta(replay);

            if (!meta.isEmpty())
            {
                context.batcher.text(context.batcher.getFont().limitToWidth(meta, textW), textX, y + 8 + fontH + 2, Colors.GRAY, false);
            }

            if (claimer != null)
            {
                int avatar = 16;
                int ax = ex - avatar - 4;
                int ay = y + (ROW_H - avatar) / 2 - 4;

                context.batcher.box(ax - 1, ay - 1, ax + avatar + 1, ay + avatar + 1, mine ? LIGHT_GREEN : GREEN);
                UIPlayerFaceRenderer.draw(context.batcher, claimer.name, claimer.uuid, ax, ay, avatar);

                String name = context.batcher.getFont().limitToWidth(claimer.name, 68);

                context.batcher.text(name, ax - 4 - context.batcher.getFont().getWidth(name), ay + 4,
                    mine ? LIGHT_GREEN : Colors.LIGHTEST_GRAY, false);
            }
        }

        /** Length, keyframe count and the flags that change what the replay does. */
        private String meta(Replay replay)
        {
            if (replay == null)
            {
                return "";
            }

            StringBuilder builder = new StringBuilder();

            builder.append(text("bbs_together.coop.room.duration", "time", TimeUtils.formatTime(replay.getLastKeyframeTick())));
            builder.append(" \u00b7 ");
            builder.append(text("bbs_together.coop.room.keyframes", "count", String.valueOf(keyframes(replay))));

            if (replay.fp.get())
            {
                builder.append(" \u00b7 ").append(text("bbs_together.coop.room.fp"));
            }

            if (replay.actor.get())
            {
                builder.append(" \u00b7 ").append(text("bbs_together.coop.room.actor"));
            }

            if (!replay.enabled.get())
            {
                builder.append(" \u00b7 ").append(text("bbs_together.coop.room.disabled"));
            }

            return builder.toString();
        }

        private int keyframes(Replay replay)
        {
            int count = 0;

            for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
            {
                count += channel.getKeyframes().size();
            }

            for (Object track : replay.properties.tracks.values())
            {
                count += ((KeyframeChannel<?>) track).getKeyframes().size();
            }

            return count;
        }

        private void renderPreview(UIContext context, int px, int py)
        {
            if (!BBSSettings.listModelPreview.get())
            {
                return;
            }

            Replay replay = this.list.replay(this.index);
            Form form = replay == null ? null : replay.form.get();

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

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            Room room = CoopClient.room;
            UUID self = CoopClient.playerId();

            /* Recording is read-only: the rows stop being a picker and become a progress log. */
            if (room == null || self == null || room.isRecording() || !this.area.isInside(context))
            {
                return false;
            }

            RoomMember claimer = claimerOf(room, this.index);

            if (context.mouseButton == 0)
            {
                if (claimer == null)
                {
                    CoopClient.sendPick(room.id, this.index);
                }

                return true;
            }

            if (context.mouseButton == 1 && claimer != null && self.equals(claimer.uuid))
            {
                CoopClient.sendPick(room.id, -1);

                return true;
            }

            return false;
        }
    }

    /* =====================================================================================
     *  Participant column
     * ===================================================================================== */

    private class MemberList extends UIElement
    {
        private static final int ROW = 22;

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            Room room = CoopClient.room;

            if (room == null)
            {
                return;
            }

            int x = this.area.x;
            int y = this.area.y;
            int ex = this.area.ex();
            int fontH = context.batcher.getFont().getHeight();
            int total = room.members.size() + room.submitted.size();
            int done = room.submitted.size();

            context.batcher.box(x, y, ex, this.area.ey(), 0x22000000);

            context.batcher.text(text("bbs_together.coop.room.members"), x + 5, y + 4, Colors.WHITE, false);

            /* Live progress: a bar plus "2/4" while recording, the pick count while still picking. */
            String counter = room.isRecording()
                ? text("bbs_together.coop.room.progress", "done", String.valueOf(done), "total", String.valueOf(Math.max(1, total)))
                : text("bbs_together.coop.room.picked", "picked", String.valueOf(picked(room)), "total", String.valueOf(Math.max(1, room.members.size())));
            int counterW = context.batcher.getFont().getWidth(counter);

            context.batcher.text(counter, ex - counterW - 5, y + 4, Colors.LIGHTER_GRAY, false);

            if (room.isRecording() && total > 0)
            {
                int barY = y + 5 + fontH + 2;

                context.batcher.box(x + 5, barY, ex - 5, barY + 3, 0x66000000);
                context.batcher.box(x + 5, barY, x + 5 + Math.round((ex - x - 10) * (done / (float) total)), barY + 3, GREEN);
            }

            int ry = y + 5 + fontH + 8;

            for (RoomMember member : room.members)
            {
                ry = this.row(context, room, member, ry, memberStatusPicking(room, member));
            }

            for (RoomMember member : room.submitted)
            {
                ry = this.row(context, room, member, ry, text("bbs_together.coop.room.member_submitted"));
            }

            for (RoomMember member : room.invited)
            {
                ry = this.row(context, room, member, ry, text("bbs_together.coop.room.member_invited"));
            }
        }

        private int row(UIContext context, Room room, RoomMember member, int ry, String status)
        {
            int x = this.area.x;
            int ex = this.area.ex();
            UUID self = CoopClient.playerId();
            boolean host = member.uuid.equals(room.host);
            boolean me = self != null && self.equals(member.uuid);
            int color = me ? LIGHT_GREEN : (host || member.pick >= 0 ? Colors.WHITE : Colors.GRAY);

            UIPlayerFaceRenderer.draw(context.batcher, member.name, member.uuid, x + 5, ry, 16);

            int statusW = context.batcher.getFont().getWidth(status);
            int nameW = Math.max(24, ex - (x + 25) - statusW - 8);

            context.batcher.text(context.batcher.getFont().limitToWidth(member.name + (host ? " \u2605" : ""), nameW),
                x + 25, ry + 4, color, false);
            context.batcher.text(status, ex - statusW - 5, ry + 4, member.pick >= 0 || host ? Colors.LIGHTEST_GRAY : Colors.GRAY, false);

            return ry + ROW;
        }

        private int picked(Room room)
        {
            int picked = 0;

            for (RoomMember member : room.members)
            {
                if (member.pick >= 0)
                {
                    picked++;
                }
            }

            return picked;
        }

        private String memberStatusPicking(Room room, RoomMember member)
        {
            if (room.isRecording())
            {
                return text("bbs_together.coop.room.member_recording");
            }

            return member.pick >= 0
                ? text("bbs_together.coop.room.member_ready", "index", String.valueOf(member.pick + 1))
                : text("bbs_together.coop.room.member_picking");
        }
    }
}
