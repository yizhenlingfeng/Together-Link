package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
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

import java.util.UUID;

/**
 * The cooperative recording picking room: replays on the left (each claimed one carries
 * the claimer's avatar), members on the right (gray = no replay yet, green = claimed),
 * everyone's mouse position drawn as a colored dot, and the host's start button that
 * only comes alive once every participant has claimed a replay.
 *
 * <p>A dedicated minimal menu (like the structure wand's save menu) so the room can open
 * no matter what the player was doing — world or dashboard — without dragging the whole
 * dashboard with it.</p>
 */
@Environment(EnvType.CLIENT)
public class UICoopRoomPanel extends UIOverlayPanel
{
    private static final int BOX_W = 190;
    private static final int BOX_H = 34;
    private static final int GAP = 26;

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
        UUID self = MinecraftClient.getInstance().player == null
            ? null
            : MinecraftClient.getInstance().player.getUuid();

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

    private class RoomView extends UIElement
    {
        public RoomView()
        {
            /* Room mouse reporting: the whole screen is the canvas, normalized to it. */
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

            UUID self = MinecraftClient.getInstance().player == null
                ? null
                : MinecraftClient.getInstance().player.getUuid();

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

            /* Left column: replay boxes */
            int primary = 0xFF000000 | mchorse.bbs_mod.BBSSettings.primaryColor.get();
            int x = this.area.x + 10;
            int y = this.area.y + 6;
            int maxBoxBottom = this.area.ey();

            for (int i = 0; i < room.replayLabels.size() && y + BOX_H < maxBoxBottom; i++)
            {
                String label = room.replayLabels.get(i);
                UUID claimer = claimerOf(room, i);
                boolean mine = self.equals(claimer);

                if (claimer != null)
                {
                    /* Claimed: green frame + the claimer's avatar sitting on the top edge. */
                    context.batcher.box(x, y, x + BOX_W, y + 1, 0xFF2FBF2F);
                    context.batcher.box(x, y + BOX_H - 1, x + BOX_W, y + BOX_H, 0xFF2FBF2F);
                    context.batcher.box(x, y, x + 1, y + BOX_H, 0xFF2FBF2F);
                    context.batcher.box(x + BOX_W - 1, y, x + BOX_W, y + BOX_H, 0xFF2FBF2F);

                    for (RoomMember member : room.members)
                    {
                        if (member.pick == i)
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
                    context.batcher.box(x, y, x + BOX_W, y + 1, primary);
                    context.batcher.box(x, y + BOX_H - 1, x + BOX_W, y + BOX_H, primary);
                    context.batcher.box(x, y, x + 1, y + BOX_H, primary);
                    context.batcher.box(x + BOX_W - 1, y, x + BOX_W, y + BOX_H, primary);
                }

                String name = label.isEmpty()
                    ? mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.room.replay").get().replace("{index}", String.valueOf(i + 1))
                    : label;

                context.batcher.text(name, x + 6, y + (BOX_H - context.batcher.getFont().getHeight()) / 2 + 1,
                    mine ? 0xFF7CFC7C : Colors.LIGHTEST_GRAY, false);

                y += BOX_H + GAP;
            }

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

        private UUID claimerOf(Room room, int index)
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

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            Room room = CoopClient.room;

            if (room == null || room.isRecording())
            {
                return super.subMouseClicked(context);
            }

            UUID self = MinecraftClient.getInstance().player == null
                ? null
                : MinecraftClient.getInstance().player.getUuid();

            if (self == null)
            {
                return super.subMouseClicked(context);
            }

            int x = this.area.x + 10;
            int y = this.area.y + 6;

            for (int i = 0; i < room.replayLabels.size(); i++)
            {
                boolean hit = context.mouseX >= x && context.mouseX < x + BOX_W
                    && context.mouseY >= y - 8 && context.mouseY < y + BOX_H;

                if (hit)
                {
                    UUID claimer = this.claimerOf(room, i);

                    if (context.mouseButton == 0)
                    {
                        if (claimer == null)
                        {
                            /* Claim a free replay. */
                            CoopClient.sendPick(room.id, i);
                        }
                        /* Clicking an own/other's claimed box does nothing — releasing
                         * goes through the right-click below. */

                        return true;
                    }
                    else if (context.mouseButton == 1 && claimer != null && claimer.equals(self))
                    {
                        /* Right-click own avatar (the claimed box) to release the replay. */
                        CoopClient.sendPick(room.id, -1);

                        return true;
                    }

                    return true; /* Locked box: swallow the click either way. */
                }

                y += BOX_H + GAP;
            }

            return super.subMouseClicked(context);
        }
    }
}
