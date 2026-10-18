package mchorse.bbs_together.network;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.BBSTogetherFix19;
import mchorse.bbs_together.ui.UICoopRoomMenu;
import mchorse.bbs_together.ui.commits.CommitUi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the cooperative recording feature: pinned invite popup state,
 * picking-room state, all C2S senders and S2C handlers, plus the popup renderer
 * and the hotkey polling that both the HUD path (in world) and the BBS-screen
 * path (over any BBS menu) share.
 *
 * <p>Popups and rooms are pure displays of server state — every transition
 * (accept, claim, start) is validated and broadcast by {@code CoopSessionManager}.</p>
 */
@Environment(EnvType.CLIENT)
public final class CoopClient {
    /** A pending invite shown in the pinned top-right popup. */
    public static class Invite {
        public final long id;
        public final UUID host;
        public final String hostName;
        public final String filmId;
        /** Branch the host records on; the invitee is moved there on accept. */
        public final String hostBranch;
        /** Accept window length; the countdown counts locally from receive time. */
        public final long durationMs;
        public final long receivedAt = System.currentTimeMillis();

        Invite(long id, UUID host, String hostName, String filmId, String hostBranch, long durationMs) {
            this.id = id;
            this.host = host;
            this.hostName = hostName;
            this.filmId = filmId;
            this.hostBranch = hostBranch == null ? "" : hostBranch;
            this.durationMs = durationMs;
        }
    }

    public static class RoomMember {
        public final UUID uuid;
        public final String name;
        public int pick = -1;

        RoomMember(UUID uuid, String name, int pick) {
            this.uuid = uuid;
            this.name = name;
            this.pick = pick;
        }
    }

    /** The picking room (host + accepted members). Replaced wholesale on every broadcast. */
    public static class Room {
        public final long id;
        public final int phase;
        public final String filmId;
        public final UUID host;
        public final List<String> replayLabels;
        public final List<RoomMember> members;
        /** Other members' mouse positions inside the room, normalized 0..1. */
        public final Map<UUID, float[]> mice = new HashMap<>();

        Room(long id, int phase, String filmId, UUID host, List<String> replayLabels, List<RoomMember> members) {
            this.id = id;
            this.phase = phase;
            this.filmId = filmId;
            this.host = host;
            this.replayLabels = replayLabels;
            this.members = members;
        }

        public boolean isRecording() {
            return this.phase == CoopSessionManager.PHASE_RECORDING;
        }
    }

    public static volatile Invite pendingInvite;
    public static volatile Room room;
    /** A room arrived while a non-BBS screen (chat, inventory) was open; open when free. */
    public static volatile boolean roomPendingOpen = false;

    /** Edge-detection state for the popup hotkeys. */
    private static boolean prevAccept = false;
    private static boolean prevDeny = false;

    private static volatile long lastMouseSentMs = 0L;

    private CoopClient() {}

    public static boolean isInSession() {
        return room != null;
    }

    /**
     * Whether THIS client is mid-coop-take: recording was remote-started and the take
     * has not been handed in yet. Drives the guards that keep vanilla's "stop recording
     * on dashboard open" reflex from killing (or crashing on) a cooperative take.
     */
    public static boolean isCoopTakeActive() {
        return room != null && room.isRecording() && TogetherClientNetwork.recordingSessionActive;
    }

    /**
     * Hands the recorder's take straight to the server under the coop session's film id
     * and the member's claimed replay index. Used when vanilla's local-apply path cannot
     * run (the film panel holds another film or nothing at all) — without this the take
     * would be dropped or written into the wrong film.
     */
    public static void sendTakeDirect(mchorse.bbs_mod.film.Recorder recorder) {
        Room current = room;

        if (current == null || !current.isRecording() || recorder == null || recorder.hasNotStarted()) {
            return;
        }

        mchorse.bbs_mod.data.types.MapType keyframes = new mchorse.bbs_mod.data.types.MapType();

        for (Object obj : recorder.keyframes.getChannels()) {
            mchorse.bbs_mod.utils.keyframes.KeyframeChannel<?> channel = (mchorse.bbs_mod.utils.keyframes.KeyframeChannel<?>) obj;

            if (!channel.isEmpty()) {
                keyframes.put(channel.getId(), channel.toData());
            }
        }

        mchorse.bbs_mod.data.types.MapType data = new mchorse.bbs_mod.data.types.MapType();

        if (!keyframes.isEmpty()) {
            data.put("keyframes", keyframes);
        }

        if (data.isEmpty()) {
            TogetherClientNetwork.recordingSessionActive = false;
            return;
        }

        BBSTogetherFix19.sendRecordingFinishedData(current.filmId, recorder.exception, data);
    }

    /* =====================================================================================
     *  C2S senders
     * ===================================================================================== */

    public static void sendInvite(String filmId, List<UUID> targets) {
        if (!TogetherClientNetwork.isConnected() || targets.isEmpty()) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeString(filmId, 256);
        buf.writeInt(targets.size());

        for (UUID target : targets) {
            buf.writeUuid(target);
        }

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_INVITE, buf);
    }

    public static void sendRespond(long sessionId, boolean accept) {
        sendRespond(sessionId, accept, null, "");
    }

    public static void sendRespond(long sessionId, boolean accept, Film filmToCommit, String branch) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }

        TogetherClientNetwork.sendCoopRespond(sessionId, accept, filmToCommit, branch);
    }

    public static void sendPick(long sessionId, int index) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(sessionId);
        buf.writeInt(index);

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_PICK, buf);
    }

    public static void sendLeave(long sessionId) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(sessionId);

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_LEAVE, buf);
    }

    public static void sendStart(long sessionId) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(sessionId);

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_START, buf);
    }

    public static void sendCancel(long sessionId) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(sessionId);

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_CANCEL, buf);
    }

    public static void sendMouseThrottled(long sessionId, float nx, float ny) {
        long now = System.currentTimeMillis();

        if (now - lastMouseSentMs < 100L || !TogetherClientNetwork.isConnected()) {
            return;
        }

        lastMouseSentMs = now;

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(sessionId);
        buf.writeFloat(nx);
        buf.writeFloat(ny);

        ClientPlayNetworking.send(TogetherServerNetwork.SERVER_COOP_MOUSE, buf);
    }

    /* =====================================================================================
     *  S2C handlers (network thread; touch state via client.execute)
     * ===================================================================================== */

    public static void handleRequest(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();
        final UUID host = buf.readUuid();
        final String hostName = buf.readString(256);
        final String filmId = buf.readString(256);
        final long durationMs = buf.readLong();
        final String hostBranch = buf.isReadable() ? buf.readString(256) : "";

        client.execute(() -> pendingInvite = new Invite(id, host, hostName, filmId, hostBranch, durationMs));
    }

    public static void handleRequestClosed(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();

        client.execute(() -> {
            if (pendingInvite != null && pendingInvite.id == id) {
                pendingInvite = null;
            }
        });
    }

    public static void handleRoom(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();
        final int phase = buf.readByte();
        final String filmId = buf.readString(256);
        final UUID host = buf.readUuid();

        int labelCount = buf.readInt();
        List<String> labels = new ArrayList<>(labelCount);

        for (int i = 0; i < labelCount; i++) {
            labels.add(buf.readString(256));
        }

        int memberCount = buf.readInt();
        List<RoomMember> members = new ArrayList<>(memberCount);

        for (int i = 0; i < memberCount; i++) {
            members.add(new RoomMember(buf.readUuid(), buf.readString(256), buf.readInt()));
        }

        client.execute(() -> {
            Room existing = room;
            Room next = new Room(id, phase, filmId, host, labels, members);

            /* Carry the mice over: the room broadcast replaces state wholesale, and the
             * mice trickle in on their own rhythm. */
            if (existing != null && existing.id == id) {
                next.mice.putAll(existing.mice);
            }

            room = next;

            if (next.isRecording()) {
                closeRoomScreen();
            } else if (!roomPendingOpen) {
                openRoomScreen();
            }
        });
    }

    public static void handleEnd(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();
        final int reason = buf.readByte();

        client.execute(() -> {
            if (pendingInvite != null && pendingInvite.id == id) {
                pendingInvite = null;
            }

            if (room != null && room.id == id) {
                room = null;
                roomPendingOpen = false;
                closeRoomScreen();

                String key;

                switch (reason) {
                    case CoopSessionManager.END_MERGED -> key = "bbs_together.coop.notify.merged";
                    case CoopSessionManager.END_HOST_LEFT -> key = "bbs_together.coop.notify.host_left";
                    case CoopSessionManager.END_ALL_DENIED -> key = "bbs_together.coop.notify.all_denied";
                    case CoopSessionManager.END_EXPIRED -> key = "bbs_together.coop.notify.expired";
                    case CoopSessionManager.END_TIMEOUT -> key = "bbs_together.coop.notify.timeout";
                    case CoopSessionManager.END_BRANCH_SWITCH_FAILED -> key = "bbs_together.coop.notify.branch_switch_failed";
                    default -> key = "bbs_together.coop.notify.disbanded";
                }

                sendMessage(key);
            }
        });
    }

    public static void handleMouse(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();
        final UUID uuid = buf.readUuid();
        final float nx = buf.readFloat();
        final float ny = buf.readFloat();

        client.execute(() -> {
            Room current = room;

            if (current != null && current.id == id) {
                current.mice.put(uuid, new float[]{nx, ny});
            }
        });
    }

    public static void reset() {
        pendingInvite = null;
        room = null;
        roomPendingOpen = false;
        prevAccept = false;
        prevDeny = false;
    }

    /* =====================================================================================
     *  Room screen management
     * ===================================================================================== */

    public static void openRoomScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Screen current = mc.currentScreen;

        if (current != null && !(current instanceof UIScreen)) {
            /* Chat, inventory, ... — don't yank the player out of it; the HUD loop opens
             * the room as soon as the screen goes away. */
            roomPendingOpen = true;
            return;
        }

        roomPendingOpen = false;

        if (UIScreen.getCurrentMenu() instanceof UICoopRoomMenu) {
            return;
        }

        mc.execute(() -> UIScreen.open(new UICoopRoomMenu()));
    }

    public static void closeRoomScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (UIScreen.getCurrentMenu() instanceof UICoopRoomMenu menu) {
            mc.execute(menu::closeThisMenu);
        }
    }

    /** Called every frame (HUD tick) while a room is pending behind a vanilla screen. */
    public static void tryOpenPendingRoom() {
        if (roomPendingOpen && room != null) {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.currentScreen == null || mc.currentScreen instanceof UIScreen) {
                openRoomScreen();
            }
        }
    }

    /* =====================================================================================
     *  Pinned popup: rendering + hotkey polling
     * ===================================================================================== */

    /**
     * Draws the pinned invite popup (top right). Only call on paths that actually end up
     * on top of everything: the HUD callback (no screen) and the UIBaseMenu render tail
     * (any BBS menu).
     */
    public static void renderPopup(Batcher2D batcher, int screenW, int screenH) {
        Invite invite = pendingInvite;

        if (invite == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - invite.receivedAt;

        if (elapsed >= invite.durationMs) {
            pendingInvite = null;
            return;
        }

        int w = 200;
        int h = 64;
        int x = screenW - w - 6;
        int y = 6;

        batcher.dropShadow(x, y, x + w, y + h, 10, 0x66000000, 0x00000000);
        batcher.box(x, y, x + w, y + h, 0xE0181818);

        /* Request line + film line */
        String host = invite.hostName;
        String title = mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.popup.request").get().replace("{player}", host);
        String film = mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.popup.film").get().replace("{film}", invite.filmId);
        int fontH = batcher.getFont().getHeight();

        batcher.text(title, x + 6, y + 6, Colors.WHITE, false);
        batcher.text(film, x + 6, y + 6 + fontH + 3, Colors.GRAY, false);

        /* Hotkey hints, straight from the (rebindable) combos */
        String accept = "[" + CoopKeys.COOP_ACCEPT.getKeyCombo() + "] "
            + mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.popup.accept").get();
        String deny = "[" + CoopKeys.COOP_DENY.getKeyCombo() + "] "
            + mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.popup.deny").get();

        batcher.text(accept, x + 6, y + 6 + (fontH + 3) * 2, 0xFF7CFC7C, false);
        batcher.text(deny, x + 6 + batcher.getFont().getWidth(accept) + 10, y + 6 + (fontH + 3) * 2, 0xFFFF7C7C, false);

        /* Countdown bar hugging the bottom edge */
        int barY = y + h - 4;
        float progress = 1F - (float) elapsed / (float) invite.durationMs;

        batcher.box(x, barY, x + w, y + h, 0xFF2A2A2A);

        if (progress > 0F) {
            batcher.box(x, barY, x + (int) (w * progress), y + h, 0xFF4C8BF5 | Colors.A100);
        }
    }

    /**
     * Popup hotkeys, edge-detected, shared by both popup render paths. Safe against
     * text fields: raw polling only runs with no screen at all, or over a BBS screen
     * whose context has nothing focused.
     */
    public static void pollPopupKeys() {
        Invite invite = pendingInvite;

        if (invite == null) {
            prevAccept = false;
            prevDeny = false;
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        Screen current = mc.currentScreen;
        boolean allow;

        if (current == null) {
            allow = true;
        } else if (current instanceof UIScreen) {
            mchorse.bbs_mod.ui.framework.UIBaseMenu menu = UIScreen.getCurrentMenu();

            allow = menu != null && menu.context != null && !menu.context.isFocused();
        } else {
            allow = false; /* Chat / inventory / other mods' screens: never hijack keys. */
        }

        if (!allow) {
            prevAccept = false;
            prevDeny = false;
            return;
        }

        boolean accept = CoopKeys.COOP_ACCEPT.isHeld();
        boolean deny = CoopKeys.COOP_DENY.isHeld();

        if (accept && !prevAccept) {
            /* The server commits the current branch and switches us to the host's branch.
             * Flush edits still inside the 100 ms throttle, and when the branches differ attach
             * the whole editor film so the commit also contains camera clips and structure
             * changes (replay deltas are the only values the server applies live). */
            Film filmToCommit = prepareInviteAccept(invite);
            pendingInvite = null;
            sendRespond(invite.id, true, filmToCommit, TogetherClientNetwork.filmBranch(invite.filmId));
        } else if (deny && !prevDeny) {
            pendingInvite = null;
            sendRespond(invite.id, false);
        }

        prevAccept = accept;
        prevDeny = deny;
    }

    /**
     * Drain queued edits and return the film the server should commit before switching branches.
     * The film travels inside the accept packet, so it arrives before the accept on the same
     * channel; returns {@code null} when the invitee is already on the host's branch.
     */
    private static Film prepareInviteAccept(Invite invite) {
        UIFilmPanel panel = CommitUi.filmPanel();

        if (panel == null || panel.getData() == null || !(panel.getData() instanceof Film)) {
            return null;
        }

        Film film = (Film) panel.getData();

        if (invite.filmId == null || !invite.filmId.equals(film.getId())) {
            return null;
        }

        TogetherEditSyncQueue.flushNow(panel);

        String localBranch = TogetherClientNetwork.filmBranch(invite.filmId);

        if (!invite.hostBranch.isEmpty() && !invite.hostBranch.equals(localBranch)) {
            /* The full film above is the commit payload; anything still queued belongs to the
             * branch we are leaving and must not be flushed under the new branch afterwards. */
            TogetherEditSyncQueue.clear();
            return film;
        }

        return null;
    }

    /** Called every frame: releases a room screen that was waiting for a vanilla screen to close. */
    public static void update() {
        tryOpenPendingRoom();
    }

    /* =====================================================================================
     *  Recording start (c_coop_begin) — mirrors the remote-toggle lambda
     * ===================================================================================== */

    public static void handleBegin(MinecraftClient client, PacketByteBuf buf) {
        final long id = buf.readLong();
        final String filmId = buf.readString(256);
        final int replayIndex = buf.readInt();

        client.execute(() -> {
            Room current = room;

            if (current == null || current.id != id) {
                return;
            }

            /* Prefer the live editor film: after accepting an invite from another branch the
             * authoritative branch state was applied in memory, but BBS may not have written
             * it to disk yet. The recorded replay must be the host branch's replay. */
            UIFilmPanel panel = CommitUi.filmPanel();
            Object panelData = panel == null ? null : panel.getData();
            Film film = panelData instanceof Film && filmId.equals(((Film) panelData).getId())
                ? (Film) panelData
                : (Film) mchorse.bbs_mod.BBSMod.getFilms().load(filmId);

            if (film == null || replayIndex < 0 || replayIndex >= film.replays.getList().size()) {
                sendMessage("bbs_together.coop.notify.begin_failed");
                return;
            }

            Films films = BBSModClient.getFilms();
            TogetherClientNetwork.lastLocalReplayIdx = replayIndex;
            mchorse.bbs_together.BBSTogetherFix28Client.sendRecordingBaselineSnapshot(filmId, film);
            films.startRecording(film, replayIndex, 0);
            TogetherClientNetwork.recordingSessionActive = true;

            closeRoomScreen();
            sendMessage("bbs_together.coop.notify.recording_started");
        });
    }

    private static void sendMessage(String key) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player != null) {
            mc.player.sendMessage(Text.translatable(key), false);
        }
    }
}
