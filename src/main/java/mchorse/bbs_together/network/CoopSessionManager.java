package mchorse.bbs_together.network;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_together.BBSTogetherFix18;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.commits.FilmBranchStore;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooperative recording sessions ("request coop recording").
 *
 * <p>One host invites players; invitees accept via the pinned popup; everyone enters a
 * picking room where each participant claims exactly one replay; the host starts the take;
 * every member records their own replay locally and hands the data back through the regular
 * {@code s_recording_finished} channel (see {@link #onRecordingFinished}); once every
 * member's data has arrived the server merges it all into the film, saves and broadcasts.</p>
 *
 * <p>All state lives here and only here; clients are told what to draw, never asked to
 * decide. Every handler runs on the server main thread ({@code server.execute}) — the
 * network threads only parse the buffer.</p>
 */
public final class CoopSessionManager {
    public static final int PHASE_PICKING = 0;
    public static final int PHASE_RECORDING = 1;

    /* End reasons (c_coop_end) */
    public static final int END_MERGED = 0;
    public static final int END_HOST_LEFT = 1;
    public static final int END_ALL_DENIED = 2;
    public static final int END_EXPIRED = 3;
    public static final int END_TIMEOUT = 4;
    public static final int END_HOST_DISBAND = 5;
    /** An invitee could not commit their own branch / switch to the session branch. */
    public static final int END_BRANCH_SWITCH_FAILED = 6;

    /** A recording session that produced no data for this long is force-closed (partial merge). */
    private static final long RECORDING_TIMEOUT_MS = 30L * 60L * 1000L;
    /** How long after a session ends client film writes stay refused (stale copies). */
    private static final long FILM_LOCK_GRACE_MS = 30_000L;

    public static class Member {
        public final UUID uuid;
        public final String name;
        public int pick = -1;

        Member(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    public static class CoopSession {
        public enum Phase { INVITING, PICKING, RECORDING }

        public final long id;
        public final String filmId;
        public final UUID host;
        /** Branch the host was on when the session was created; invitees are moved onto it
         * (after committing their own branch) when they accept. */
        public String branch;
        /** Host + everyone who accepted, in join order. */
        public final Map<UUID, Member> members = new LinkedHashMap<>();
        /** Players invited but not (yet) accepted. */
        public final Map<UUID, Member> invited = new LinkedHashMap<>();
        public Phase phase = Phase.INVITING;
        /** Accept window: absolute epoch ms. */
        public long deadline;
        public long recordingStartedAt;
        /** Members whose recording data the server is still waiting for (RECORDING phase). */
        public final Set<UUID> pendingData = new HashSet<>();
        /** Recording data that already arrived: uuid -> one replay's payload. */
        public final Map<UUID, MergedTake> received = new LinkedHashMap<>();
        /** How many replays the film had when the session was created (pick validation). */
        public int replayCount;

        CoopSession(long id, String filmId, UUID host) {
            this.id = id;
            this.filmId = filmId;
            this.host = host;
        }

        public boolean isRecording() {
            return this.phase == Phase.RECORDING;
        }
    }

    /** One member's take: the replay index they recorded plus the data to merge into it. */
    public static class MergedTake {
        public final int replayIndex;
        public final MapType data;

        MergedTake(int replayIndex, MapType data) {
            this.replayIndex = replayIndex;
            this.data = data;
        }
    }

    private static final Map<Long, CoopSession> sessions = new HashMap<>();
    /** Any participant (invited OR accepted) -> session; one player, one session. */
    private static final Map<UUID, Long> membership = new HashMap<>();
    /** filmId -> active session; one film, one session. */
    private static final Map<String, Long> filmSessions = new HashMap<>();
    /**
     * filmId -> lock expiry. While a coop session owns a film — and for a grace window
     * after it ends — client-side film writes (film_save_sync / film_data_sync) are
     * refused: members' local copies hold only their own take, and one of those stale
     * copies pushed back would wipe the merged takes of everyone else.
     */
    private static final Map<String, Long> filmLocks = new ConcurrentHashMap<>();
    private static long nextId = 1L;

    private CoopSessionManager() {}

    private static long inviteDurationMs() {
        try {
            return ((Number) TogetherSettings.coopInviteSeconds.get()).intValue() * 1000L;
        } catch (Exception e) {
            return 60_000L;
        }
    }

    /** Whether the player is part of any coop session (invited or accepted). */
    public static boolean isPlayerInSession(UUID uuid) {
        return membership.containsKey(uuid);
    }

    /**
     * Whether one branch of the film is under coop control right now (active session, or the
     * post-merge grace window): server-side writes to that branch must be refused.
     */
    public static boolean isFilmLockedForEditing(String filmId, String branch) {
        if (filmId == null || branch == null) {
            return false;
        }

        Long sessionId = filmSessions.get(filmId);

        if (sessionId != null) {
            CoopSession session = sessions.get(sessionId);

            if (session != null && branch.equals(session.branch)) {
                return true;
            }
        }

        Long expiry = filmLocks.get(lockKey(filmId, branch));

        return expiry != null && expiry > System.currentTimeMillis();
    }

    private static String lockKey(String filmId, String branch) {
        return filmId + '\t' + branch;
    }

    /* =====================================================================================
     *  C2S handlers (network thread -> parse only; all logic in server.execute)
     * ===================================================================================== */

    public static void handleInvite(MinecraftServer server, ServerPlayerEntity host, PacketByteBuf buf) {
        String filmId = buf.readString(256);
        int count = buf.readInt();

        if (count < 0 || count > 32) {
            return;
        }

        List<UUID> targets = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            targets.add(buf.readUuid());
        }

        server.execute(() -> invite(server, host, filmId, targets));
    }

    public static void handleRespond(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        /* Chunked: an accept from another branch may carry the invitee's full film so the
         * automatic commit contains camera clips and structure edits, not just replay data. */
        TogetherServerNetwork.receiveCrusher(player, buf, (bytes, packetByteBuf) -> {
            long sessionId = packetByteBuf.readLong();
            boolean accept = packetByteBuf.readBoolean();
            String sentBranch = packetByteBuf.isReadable() ? packetByteBuf.readString(256) : "";
            MapType incomingFilm = TogetherServerNetwork.readFilmPayload(bytes);

            server.execute(() -> respond(server, player, sessionId, accept, incomingFilm, sentBranch));
        });
    }

    public static void handlePick(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long sessionId = buf.readLong();
        int index = buf.readInt();

        server.execute(() -> pick(server, player, sessionId, index));
    }

    public static void handleLeave(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long sessionId = buf.readLong();

        server.execute(() -> leave(server, player, sessionId));
    }

    public static void handleStart(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long sessionId = buf.readLong();

        server.execute(() -> start(server, player, sessionId));
    }

    public static void handleCancel(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long sessionId = buf.readLong();

        server.execute(() -> hostCancel(server, player, sessionId));
    }

    public static void handleMouse(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf) {
        long sessionId = buf.readLong();
        float nx = buf.readFloat();
        float ny = buf.readFloat();

        server.execute(() -> relayMouse(server, player, sessionId, nx, ny));
    }

    /* =====================================================================================
     *  Session logic (server main thread only)
     * ===================================================================================== */

    private static void invite(MinecraftServer server, ServerPlayerEntity host, String filmId, List<UUID> targets) {
        UUID hostId = host.getUuid();

        if (filmId == null || filmId.isEmpty() || membership.containsKey(hostId)) {
            sendChat(host, "bbs_together.coop.error.busy");
            return;
        }

        if (filmSessions.containsKey(filmId)) {
            sendChat(host, "bbs_together.coop.error.film_busy");
            return;
        }

        /* Inviting is allowed for editors (level 1) and admins (level 2). Domain whitelists
         * restrict editing; they should not hide the coop-recording entry point. */
        if (BbsOpManager.get(hostId) < BbsOpManager.LEVEL_EDITOR) {
            sendChat(host, "bbs_together.coop.error.no_record");
            return;
        }

        String branch = FilmBranchStore.branchOf(filmId, hostId);
        Film film = FilmBranchStore.working(filmId, branch);

        if (film == null) {
            sendChat(host, "bbs_together.coop.error.no_film");
            return;
        }

        int replayCount = film.replays.getList().size();

        /* The host claims a replay too, hence targets + 1 boxes are needed. */
        if (replayCount < targets.size() + 1) {
            sendChat(host, "bbs_together.coop.error.not_enough_replays");
            return;
        }

        Set<UUID> seen = new HashSet<>();
        List<ServerPlayerEntity> targetPlayers = new ArrayList<>();

        for (UUID target : targets) {
            if (target.equals(hostId) || !seen.add(target) || membership.containsKey(target)) {
                sendChat(host, "bbs_together.coop.error.busy");
                return;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(target);

            if (player == null) {
                sendChat(host, "bbs_together.coop.error.offline");
                return;
            }

            if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM) || !BbsOpManager.isAllowed(player, BbsOpDomain.RECORD)) {
                sendChat(host, "bbs_together.coop.error.no_record");
                return;
            }

            /* A coop take records into one film branch, but invitees may still be on another
             * branch. They keep working there while the invite is pending; accepting commits
             * that branch and moves them onto the host's branch (see respond()). */
            targetPlayers.add(player);
        }

        CoopSession session = new CoopSession(nextId++, filmId, hostId);

        session.replayCount = replayCount;
        session.branch = branch;
        session.members.put(hostId, new Member(hostId, host.getGameProfile().getName()));
        session.deadline = System.currentTimeMillis() + inviteDurationMs();

        for (ServerPlayerEntity player : targetPlayers) {
            session.invited.put(player.getUuid(), new Member(player.getUuid(), player.getGameProfile().getName()));
        }

        sessions.put(session.id, session);
        filmSessions.put(filmId, session.id);
        membership.put(hostId, session.id);

        for (UUID target : targets) {
            membership.put(target, session.id);
        }

        System.out.println("[BBSTogether] Coop session " + session.id + " created by " + host.getGameProfile().getName()
            + " for film " + filmId + " with " + targets.size() + " invitee(s)");

        for (ServerPlayerEntity player : targetPlayers) {
            PacketByteBuf out = PacketByteBufs.create();

            out.writeLong(session.id);
            out.writeUuid(hostId);
            out.writeString(host.getGameProfile().getName(), 256);
            out.writeString(filmId, 256);
            out.writeLong(session.deadline - System.currentTimeMillis());
            /* The host's branch travels along so the invitee can upload its full film before
             * accepting when the two are on different branches (see CoopClient). */
            out.writeString(session.branch == null ? "" : session.branch, 256);

            ServerPlayNetworking.send(player, TogetherServerNetwork.CLIENT_COOP_REQUEST, out);
        }
    }

    private static void respond(MinecraftServer server, ServerPlayerEntity player, long sessionId, boolean accept, MapType incomingFilm, String sentBranch) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !Long.valueOf(sessionId).equals(membership.get(uuid)) || session.isRecording()) {
            return;
        }

        if (accept) {
            if (System.currentTimeMillis() > session.deadline) {
                return; /* Late accept after the window: treated as a decline. */
            }

            Member invited = session.invited.remove(uuid);

            if (invited != null && !session.members.containsKey(uuid)) {
                session.members.put(uuid, invited);
            }

            /* The invite is about the host's branch. An invitee coming from another branch
             * keeps their own work: commit that branch, then check out the session branch.
             * Only after the move succeeds may they join the room. */
            if (!TogetherServerNetwork.prepareCoopBranchSwitch(server, player, session.filmId, session.branch, incomingFilm, sentBranch)) {
                session.members.remove(uuid);
                session.invited.remove(uuid);
                membership.remove(uuid);
                sendChat(player, "bbs_together.coop.error.branch_switch");

                ServerPlayerEntity hostPlayer = server.getPlayerManager().getPlayer(session.host);

                if (hostPlayer != null) {
                    hostPlayer.sendMessage(Text.literal("§e[BBS Together] " + player.getGameProfile().getName() + " 无法自动提交并切换分支，未能接受邀请"), false);
                }

                if (session.invited.isEmpty() && session.members.size() <= 1) {
                    end(server, session, END_BRANCH_SWITCH_FAILED);
                } else if (session.phase == CoopSession.Phase.PICKING) {
                    /* Other members already opened the room; drop this one from their list.
                     * While still INVITING nobody has a room to update. */
                    broadcastRoom(server, session);
                }

                return;
            }

            /* First accept opens the room; later accepts (before the deadline) join it. */
            session.phase = CoopSession.Phase.PICKING;

            broadcastRoom(server, session);
        } else {
            session.invited.remove(uuid);
            membership.remove(uuid);

            /* Everyone the host asked has said no — nothing to wait for. */
            if (session.invited.isEmpty() && session.members.size() <= 1) {
                end(server, session, END_ALL_DENIED);
            }
        }
    }

    private static void pick(MinecraftServer server, ServerPlayerEntity player, long sessionId, int index) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !session.members.containsKey(uuid) || session.phase != CoopSession.Phase.PICKING) {
            return;
        }

        Member member = session.members.get(uuid);

        if (index == -1) {
            member.pick = -1;
        } else {
            if (index < 0 || index >= session.replayCount) {
                return;
            }

            for (Member other : session.members.values()) {
                if (!other.uuid.equals(uuid) && other.pick == index) {
                    return; /* Someone else already claimed that replay. */
                }
            }

            member.pick = index;
        }

        broadcastRoom(server, session);
    }

    private static void leave(MinecraftServer server, ServerPlayerEntity player, long sessionId) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !Long.valueOf(sessionId).equals(membership.get(uuid))) {
            return;
        }

        if (uuid.equals(session.host)) {
            end(server, session, END_HOST_LEFT);
            return;
        }

        if (session.isRecording()) {
            /* Leaving mid-recording = dropping out of the take; their partial data, if it
             * arrives later, is still merged (the session lives until the merge or timeout). */
            session.pendingData.remove(uuid);
        }

        session.members.remove(uuid);
        membership.remove(uuid);

        if (session.isRecording()) {
            tryFinishRecording(server, session);
        } else if (session.members.isEmpty()) {
            end(server, session, END_HOST_LEFT);
        } else {
            broadcastRoom(server, session);
        }
    }

    private static void start(MinecraftServer server, ServerPlayerEntity player, long sessionId) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !uuid.equals(session.host) || session.phase != CoopSession.Phase.PICKING) {
            return;
        }

        /* Every participant must have claimed a replay (host included). */
        for (Member member : session.members.values()) {
            if (member.pick < 0) {
                return;
            }
        }

        /* Re-validate against the live film: replays could have been touched since claiming. */
        Film film = FilmBranchStore.working(session.filmId, session.branch);

        if (film == null || film.replays.getList().size() < session.replayCount) {
            end(server, session, END_HOST_DISBAND);
            return;
        }

        session.phase = CoopSession.Phase.RECORDING;
        session.recordingStartedAt = System.currentTimeMillis();
        session.pendingData.clear();
        session.pendingData.addAll(session.members.keySet());

        System.out.println("[BBSTogether] Coop session " + session.id + " started recording ("
            + session.members.size() + " member(s))");

        for (Member member : session.members.values()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(member.uuid);

            if (p != null) {
                PacketByteBuf out = PacketByteBufs.create();

                out.writeLong(session.id);
                out.writeString(session.filmId, 256);
                out.writeInt(member.pick);

                ServerPlayNetworking.send(p, TogetherServerNetwork.CLIENT_COOP_BEGIN, out);
            }
        }

        broadcastRoom(server, session);
    }

    private static void hostCancel(MinecraftServer server, ServerPlayerEntity player, long sessionId) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !uuid.equals(session.host)) {
            return;
        }

        if (session.isRecording()) {
            return; /* Once recording, the take only ends by handing in or leaving. */
        }

        end(server, session, END_HOST_DISBAND);
    }

    private static void relayMouse(MinecraftServer server, ServerPlayerEntity player, long sessionId, float nx, float ny) {
        CoopSession session = sessions.get(sessionId);
        UUID uuid = player.getUuid();

        if (session == null || !session.members.containsKey(uuid) || nx < -0.1F || nx > 1.1F || ny < -0.1F || ny > 1.1F) {
            return;
        }

        for (UUID memberId : session.members.keySet()) {
            if (memberId.equals(uuid)) {
                continue;
            }

            ServerPlayerEntity p = server.getPlayerManager().getPlayer(memberId);

            if (p != null) {
                PacketByteBuf out = PacketByteBufs.create();

                out.writeLong(sessionId);
                out.writeUuid(uuid);
                out.writeFloat(nx);
                out.writeFloat(ny);

                ServerPlayNetworking.send(p, TogetherServerNetwork.CLIENT_COOP_MOUSE, out);
            }
        }
    }

    /* =====================================================================================
     *  Recording data collection (called from TogetherServerNetwork.handleRecordingFinished)
     * ===================================================================================== */

    /**
     * Consumes a finished take when its player belongs to an active recording session.
     * Returns true when handled (the legacy per-player merge path must then be skipped).
     */
    public static boolean onRecordingFinished(MinecraftServer server, ServerPlayerEntity player, String filmId, int replayIndex, MapType replayData) {
        UUID uuid = player.getUuid();
        Long sessionId = membership.get(uuid);
        CoopSession session = sessionId == null ? null : sessions.get(sessionId);

        if (session == null || !session.isRecording() || !session.pendingData.contains(uuid) || !session.filmId.equals(filmId)) {
            return false;
        }

        Member member = session.members.get(uuid);
        int index = member != null && member.pick >= 0 ? member.pick : replayIndex;

        session.received.put(uuid, new MergedTake(index, replayData));
        session.pendingData.remove(uuid);
        session.members.remove(uuid); /* Handed in and out of the room; the session lives until the merge. */
        membership.remove(uuid);

        tryFinishRecording(server, session);

        return true;
    }

    private static void tryFinishRecording(MinecraftServer server, CoopSession session) {
        if (!session.pendingData.isEmpty()) {
            return;
        }

        Film film = FilmBranchStore.working(session.filmId, session.branch);

        if (film != null) {
            List<Replay> replays = film.replays.getList();
            List<String> mergedNames = new ArrayList<>();
            int merged = 0;

            for (MergedTake take : session.received.values()) {
                if (take.replayIndex < 0 || take.replayIndex >= replays.size()) {
                    continue;
                }

                BBSTogetherFix18.mergeReplayData(replays.get(take.replayIndex), take.data);
                mergedNames.add(session.filmId + "#" + take.replayIndex);
                merged++;
            }

            MapType filmData = (MapType) film.toData();

            FilmBranchStore.setWorking(session.filmId, session.branch, filmData);
            FilmBranchStore.save(session.filmId, session.branch);

            /* Push the authoritative merged film to every session participant
             * unconditionally: broadcastFilmSaved only reaches players who are editing
             * the film or have its screen open, and a member who is back in the world
             * would keep a stale local copy (only their own take) that a later save of
             * theirs would push back over the merge. */
            Set<UUID> participants = new HashSet<>(session.received.keySet());

            participants.addAll(session.members.keySet());

            for (UUID uuid : participants) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);

                if (p != null) {
                    TogetherServerNetwork.broadcastFilmSavedToAll(server, p, session.filmId, session.branch, filmData);
                }
            }

            TogetherServerNetwork.broadcastFilmSaved(server, session.filmId, session.branch, filmData);

            /* One audit row for the whole session, attributed to the host when online. */
            ServerPlayerEntity actor = server.getPlayerManager().getPlayer(session.host);

            if (actor == null && !session.received.isEmpty()) {
                actor = server.getPlayerManager().getPlayer(session.received.keySet().iterator().next());
            }

            if (actor != null) {
                Map<String, String> extra = new HashMap<>();

                extra.put("sessionId", String.valueOf(session.id));
                extra.put("host", nameOf(server, session.host));
                extra.put("members", String.valueOf(merged));

                for (Map.Entry<UUID, MergedTake> entry : session.received.entrySet()) {
                    extra.put("take:" + nameOf(server, entry.getKey()), String.valueOf(entry.getValue().replayIndex));
                }

                BehaviorLogManager.record(actor, BehaviorType.EDIT.id, BehaviorType.SUB_COOP_RECORDING,
                    session.filmId, mergedNames.isEmpty() ? Collections.singletonList(session.filmId) : mergedNames, extra);
            }

            System.out.println("[BBSTogether] Coop session " + session.id + " merged " + merged
                + " take(s) into " + session.filmId);
        }

        end(server, session, END_MERGED);
    }

    /* =====================================================================================
     *  Lifecycle: disconnect, sweep, end, room broadcast
     * ===================================================================================== */

    public static void onDisconnect(UUID uuid, MinecraftServer server) {
        Long sessionId = membership.remove(uuid);
        CoopSession session = sessionId == null ? null : sessions.get(sessionId);

        if (session == null) {
            return;
        }

        if (uuid.equals(session.host)) {
            end(server, session, END_HOST_LEFT);
            return;
        }

        session.invited.remove(uuid);
        session.pendingData.remove(uuid);
        session.members.remove(uuid);

        if (session.isRecording()) {
            tryFinishRecording(server, session);
        } else if (session.members.isEmpty()) {
            end(server, session, END_HOST_LEFT);
        } else {
            broadcastRoom(server, session);
        }
    }

    /** Runs every server tick: invite expiry + recording timeout + expired film locks. */
    public static void sweep(MinecraftServer server) {
        if (!filmLocks.isEmpty()) {
            filmLocks.values().removeIf(expiry -> expiry <= System.currentTimeMillis());
        }

        if (sessions.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        for (CoopSession session : new ArrayList<>(sessions.values())) {
            boolean pastDeadline = now > session.deadline;

            if (session.phase == CoopSession.Phase.INVITING && pastDeadline && !session.invited.isEmpty()) {
                /* Nobody accepted before the window closed. */
                end(server, session, END_EXPIRED);
            } else if (session.phase == CoopSession.Phase.PICKING && pastDeadline && !session.invited.isEmpty()) {
                /* Some accepted and are already in the room; the ones who sat on the popup
                 * past the window are quietly released (they could never accept anymore). */
                for (UUID uuid : new ArrayList<>(session.invited.keySet())) {
                    session.invited.remove(uuid);
                    membership.remove(uuid, session.id);
                }
            } else if (session.isRecording() && now - session.recordingStartedAt > RECORDING_TIMEOUT_MS) {
                session.pendingData.clear();
                tryFinishRecording(server, session);
            }
        }
    }

    private static void end(MinecraftServer server, CoopSession session, int reason) {
        sessions.remove(session.id);

        if (Long.valueOf(session.id).equals(filmSessions.get(session.filmId))) {
            filmSessions.remove(session.filmId);
        }

        /* Keep refusing client-side film writes for a grace window: members' local copies
         * are stale until the merge broadcast lands (and members outside the film screen
         * only get it through the unconditional push above). */
        filmLocks.put(lockKey(session.filmId, session.branch), System.currentTimeMillis() + FILM_LOCK_GRACE_MS);

        Set<UUID> participants = new HashSet<>();

        participants.addAll(session.members.keySet());
        participants.addAll(session.invited.keySet());
        /* Members who already handed in their take were moved out of `members` — they
         * still need the end packet or their client keeps the session state forever. */
        participants.addAll(session.received.keySet());

        session.members.clear();
        session.invited.clear();

        for (UUID uuid : participants) {
            membership.remove(uuid, session.id);

            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);

            if (p != null) {
                PacketByteBuf out = PacketByteBufs.create();

                out.writeLong(session.id);
                out.writeByte((byte) reason);

                ServerPlayNetworking.send(p, TogetherServerNetwork.CLIENT_COOP_END, out);
            }
        }

        System.out.println("[BBSTogether] Coop session " + session.id + " ended (reason " + reason + ")");
    }

    /** Pushes the full room state to host + members (the room UI is fully server-driven). */
    private static void broadcastRoom(MinecraftServer server, CoopSession session) {
        Film film = FilmBranchStore.working(session.filmId, session.branch);
        List<String> labels = new ArrayList<>();

        if (film != null) {
            for (Replay replay : film.replays.getList()) {
                String label = replay.label.get();

                labels.add(label == null ? "" : label);
            }
        }

        for (UUID uuid : session.members.keySet()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);

            if (p == null) {
                continue;
            }

            PacketByteBuf out = PacketByteBufs.create();

            out.writeLong(session.id);
            out.writeByte(session.phase == CoopSession.Phase.RECORDING ? PHASE_RECORDING : PHASE_PICKING);
            out.writeString(session.filmId, 256);
            out.writeUuid(session.host);
            out.writeInt(labels.size());

            for (String label : labels) {
                out.writeString(label, 256);
            }

            out.writeInt(session.members.size());

            for (Member member : session.members.values()) {
                out.writeUuid(member.uuid);
                out.writeString(member.name, 256);
                out.writeInt(member.pick);
            }

            ServerPlayNetworking.send(p, TogetherServerNetwork.CLIENT_COOP_ROOM, out);
        }
    }

    private static String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);

        return player != null ? player.getGameProfile().getName() : uuid.toString();
    }

    private static void sendChat(ServerPlayerEntity player, String langKey) {
        player.sendMessage(Text.translatable(langKey), false);
    }

    public static void reset() {
        sessions.clear();
        membership.clear();
        filmSessions.clear();
        filmLocks.clear();
        nextId = 1L;
    }
}
