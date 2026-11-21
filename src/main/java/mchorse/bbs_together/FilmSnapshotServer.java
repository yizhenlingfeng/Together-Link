package mchorse.bbs_together;

import mchorse.bbs_together.network.TogetherServerNetworking;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.locks.ClipLockGuard;
import mchorse.bbs_together.network.CoopSessionManager;
import mchorse.bbs_together.network.CompressedServerPacketCrusher;
import mchorse.bbs_together.FilmChannels;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public final class FilmSnapshotServer {
    private static final ConcurrentHashMap<String, LinkedHashSet<UUID>> FILM_ENTRY_ORDER = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> ACTIVE_FILM_EDITORS = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, CompressedServerPacketCrusher> SNAPSHOT_CRUSHERS = new ConcurrentHashMap();
    private static final ConcurrentHashMap<String, Long> RECORDING_BASELINE_ACCEPTED_AT = new ConcurrentHashMap();
    private static final long RECORDING_BASELINE_DEDUP_MS = 2000L;
    private static volatile boolean serverReceiversRegistered;

    private FilmSnapshotServer() {
    }

    public static void registerReceivers() {
        if (serverReceiversRegistered) {
            return;
        }
        serverReceiversRegistered = true;
        TogetherServerNetworking.register((Identifier)FilmChannels.SERVER_FILM_JOIN_REQUEST, (minecraftServer, class_32222, class_32442, class_25402, packetSender) -> {
            String string = class_25402.readString();
            minecraftServer.execute(() -> FilmSnapshotServer.handleJoinRequest(minecraftServer, class_32222, string));
        });
        TogetherServerNetworking.register((Identifier)FilmChannels.SERVER_FILM_JOIN_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            CompressedServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                UUID uUID = class_25402.readUuid();
                String string = class_25402.readString();
                minecraftServer.execute(() -> FilmSnapshotServer.handleJoinSnapshot(minecraftServer, class_32222, uUID, string, byArray));
            });
        });
        TogetherServerNetworking.register((Identifier)FilmChannels.SERVER_FILM_LEAVE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            CompressedServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                String branch = class_25402.isReadable() ? class_25402.readString(128) : "";
                minecraftServer.execute(() -> FilmSnapshotServer.handleLeaveSnapshot(minecraftServer, class_32222, string, branch, byArray));
            });
        });
        TogetherServerNetworking.register((Identifier)FilmChannels.SERVER_FILM_STRUCTURE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            CompressedServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                String branch = class_25402.isReadable() ? class_25402.readString(128) : "";
                minecraftServer.execute(() -> FilmSnapshotServer.handleStructureSnapshot(minecraftServer, class_32222, string, branch, byArray));
            });
        });
        TogetherServerNetworking.register((Identifier)FilmChannels.SERVER_RECORDING_BASELINE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            CompressedServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                String branch = class_25402.isReadable() ? class_25402.readString(128) : "";
                minecraftServer.execute(() -> FilmSnapshotServer.handleRecordingBaselineSnapshot(class_32222, string, branch, byArray));
            });
        });
    }

    public static void resetState() {
        FILM_ENTRY_ORDER.clear();
        ACTIVE_FILM_EDITORS.clear();
        SNAPSHOT_CRUSHERS.clear();
    }

    private static boolean isServerAlive(MinecraftServer server) {
        return server != null && !server.isStopping() && !server.isStopped();
    }

    private static void handleJoinRequest(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string2) {
        UUID uUID = class_32222.getUuid();
        FilmSnapshotServer.removeFromAllEntryOrders(uUID);
        if (string2 == null || string2.isEmpty()) {
            ACTIVE_FILM_EDITORS.remove(uUID);
            FilmSnapshotServer.pruneOffline(minecraftServer);
            return;
        }
        FilmSnapshotServer.pruneOffline(minecraftServer);
        ACTIVE_FILM_EDITORS.put(uUID, string2);
        FILM_ENTRY_ORDER.computeIfAbsent(string2, film -> new LinkedHashSet()).add(uUID);
        /* The server owns every branch's working copy. Asking another client for a snapshot
         * would hand the newcomer that player's branch, not theirs. The film itself was often
         * just delivered by the manager load, so only ask for a full copy when it is stale. */
        TogetherServerNetwork.syncFilmBranchState(minecraftServer, class_32222, string2);
    }

    private static Map<UUID, String> getTogetherFilmSelections() {
        try {
            Field field = TogetherServerNetwork.class.getDeclaredField("playerEditingFilm");
            field.setAccessible(true);
            Object object = field.get(null);
            if (object instanceof Map) {
                Map map = (Map)object;
                return map;
            }
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
        return Map.of();
    }

    private static void handleJoinSnapshot(MinecraftServer minecraftServer, ServerPlayerEntity sender, UUID uUID, String string, byte[] byArray) {
        if (!isServerAlive(minecraftServer) || sender == null || uUID == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        if (!uUID.equals(sender.getUuid())) {
            return;
        }
        try {
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            if (baseType == null || !baseType.isMap()) {
                return;
            }
            MapType mapType = baseType.asMap();
            String branch = FilmBranchStore.branchOf(string, sender.getUuid());
            if (!FilmSnapshotServer.canWriteFilmSnapshot(sender, string, branch, false, mapType)) {
                return;
            }
            FilmSnapshotServer.saveBranchSnapshot(string, branch, mapType);
            TogetherServerNetwork.broadcastFilmSavedToAll(minecraftServer, sender, string, branch, mapType);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleLeaveSnapshot(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string, String sentBranch, byte[] byArray) {
        if (class_32222 == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        String branch = FilmBranchStore.branchOf(string, class_32222.getUuid());
        if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
            /* A snapshot from the branch this player just left; it must not replace the
             * working copy of the branch they are on now. */
            return;
        }
        BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
        if (baseType == null || !baseType.isMap()) {
            return;
        }
        MapType mapType = baseType.asMap();
        if (!FilmSnapshotServer.canWriteFilmSnapshot(class_32222, string, branch, false, mapType)) {
            return;
        }
        ACTIVE_FILM_EDITORS.remove(class_32222.getUuid(), string);
        FilmSnapshotServer.saveBranchSnapshot(string, branch, mapType);
        if (FilmSnapshotServer.hasOtherEditor(class_32222.getUuid(), string, branch)) {
            return;
        }
        TogetherServerNetwork.broadcastFilmSaved(minecraftServer, string, branch, mapType);
    }

    private static void handleStructureSnapshot(MinecraftServer minecraftServer, ServerPlayerEntity player, String string, String sentBranch, byte[] byArray) {
        if (!isServerAlive(minecraftServer) || player == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        try {
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            if (baseType == null || !baseType.isMap()) {
                return;
            }
            MapType mapType = baseType.asMap();
            String branch = FilmBranchStore.branchOf(string, player.getUuid());
            if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
                return;
            }
            if (!FilmSnapshotServer.canWriteFilmSnapshot(player, string, branch, false, mapType)) {
                return;
            }
            Film film = FilmBranchStore.working(string, branch);
            Film film2 = new Film();
            film2.fromData((BaseType)mapType);
            FilmSnapshotServer.remapReplaySelections(string, branch, film, film2);
            FilmSnapshotServer.saveBranchSnapshot(string, branch, mapType);
            TogetherServerNetwork.broadcastFilmSaved(minecraftServer, string, branch, mapType);
            TogetherServerNetwork.broadcastReplaySelections(minecraftServer, string);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Save a client snapshot into its branch workspace; the default branch also updates the main file. */
    private static void saveBranchSnapshot(String filmId, String branch, MapType data) {
        FilmBranchStore.setWorking(filmId, branch, data);
        FilmBranchStore.save(filmId, branch);
    }

    /**
     * Every snapshot is a film write. Keep the same gates as the normal film save path:
     * FILM domain, per-film asset lock, deletion tombstone, RECORD for recording baselines,
     * the cooperative-recording write lock and the per-clip locks inside the snapshot.
     */
    private static boolean canWriteFilmSnapshot(ServerPlayerEntity player, String filmId, String branch, boolean recording, MapType incoming) {
        if (player == null || filmId == null || filmId.isEmpty()) {
            return false;
        }
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return false;
        }
        if (recording && !BbsOpManager.isAllowed(player, BbsOpDomain.RECORD)) {
            return false;
        }
        if (TogetherServerNetwork.isFilmDeleted(filmId)) {
            return false;
        }
        if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, filmId)) {
            if (player.getEntityWorld().getServer() != null) {
                AssetLockManager.notifyLocked(player.getEntityWorld().getServer(), player, AssetLockType.FILM);
            }

            return false;
        }

        if (CoopSessionManager.isFilmLockedForEditing(filmId, branch)) {
            return false;
        }

        return ClipLockGuard.canWrite(player, filmId, branch, incoming);
    }

    private static void handleRecordingBaselineSnapshot(ServerPlayerEntity player, String string, String sentBranch, byte[] byArray) {
        if (string == null || string.isEmpty() || byArray == null) {
            return;
        }
        long l = System.currentTimeMillis();
        Long l2 = RECORDING_BASELINE_ACCEPTED_AT.get(string);
        if (l2 != null && l - l2 < 2000L) {
            return;
        }
        BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
        if (baseType == null || !baseType.isMap()) {
            return;
        }
        RECORDING_BASELINE_ACCEPTED_AT.put(string, l);
        String branch = FilmBranchStore.branchOf(string, player.getUuid());
        if (!sentBranch.isEmpty() && !sentBranch.equals(branch)) {
            return;
        }
        if (!FilmSnapshotServer.canWriteFilmSnapshot(player, string, branch, true, baseType.asMap())) {
            return;
        }
        FilmSnapshotServer.saveBranchSnapshot(string, branch, baseType.asMap());
    }

    private static boolean hasOtherEditor(UUID uUID, String string, String branch) {
        for (Map.Entry<UUID, String> entry : ACTIVE_FILM_EDITORS.entrySet()) {
            if (entry.getKey().equals(uUID) || !string.equals(entry.getValue())) continue;
            if (!branch.equals(FilmBranchStore.branchOf(string, entry.getKey()))) continue;
            return true;
        }
        return false;
    }

    private static void remapReplaySelections(String string, String branch, Film film, Film film2) {
        Map map;
        Object object;
        if (film == null || film2 == null) {
            return;
        }
        List list = film.replays.getList();
        List list2 = film2.replays.getList();
        if (list.size() == list2.size()) {
            return;
        }
        Map<UUID, String> map2 = FilmSnapshotServer.getTogetherFilmSelections();
        try {
            object = TogetherServerNetwork.class.getDeclaredField("playerReplaySelections");
            ((Field)object).setAccessible(true);
            map = (Map)((Field)object).get(null);
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
            return;
        }
        object = map;
        synchronized (object) {
            for (Map.Entry<UUID, Integer> entry : new ArrayList<Map.Entry<UUID, Integer>>(map.entrySet())) {
                UUID uUID = entry.getKey();
                if (!string.equals(map2.get(uUID)) || !branch.equals(FilmBranchStore.branchOf(string, uUID))) continue;
                int n = entry.getValue();
                int n2 = FilmSnapshotServer.remapReplayIndex(list, list2, n);
                map.remove(uUID);
                if (n2 < 0) continue;
                map.put(uUID, n2);
            }
        }
    }

    private static int remapReplayIndex(List<Replay> list, List<Replay> list2, int n) {
        int n2;
        if (n < 0 || list2.isEmpty()) {
            return -1;
        }
        if (n >= list.size()) {
            return Math.min(n, list2.size() - 1);
        }
        String string = FilmSnapshotServer.replaySignature(list.get(n));
        boolean[] blArray = new boolean[list2.size()];
        block0: for (n2 = 0; n2 < list.size(); ++n2) {
            if (n2 == n) continue;
            String string2 = FilmSnapshotServer.replaySignature(list.get(n2));
            for (int i = 0; i < list2.size(); ++i) {
                if (blArray[i] || !string2.equals(FilmSnapshotServer.replaySignature(list2.get(i)))) continue;
                blArray[i] = true;
                continue block0;
            }
        }
        for (n2 = 0; n2 < list2.size(); ++n2) {
            if (blArray[n2] || !string.equals(FilmSnapshotServer.replaySignature(list2.get(n2)))) continue;
            return n2;
        }
        return Math.min(n, list2.size() - 1);
    }

    private static String replaySignature(Replay replay) {
        return replay == null ? "" : replay.toData().toString();
    }

    private static void pruneOffline(MinecraftServer minecraftServer) {
        ACTIVE_FILM_EDITORS.entrySet().removeIf(entry -> minecraftServer.getPlayerManager().getPlayer((UUID)entry.getKey()) == null);
        for (LinkedHashSet<UUID> linkedHashSet : FILM_ENTRY_ORDER.values()) {
            Iterator iterator = linkedHashSet.iterator();
            while (iterator.hasNext()) {
                if (minecraftServer.getPlayerManager().getPlayer((UUID)iterator.next()) != null) continue;
                iterator.remove();
            }
        }
    }

    private static void removeFromAllEntryOrders(UUID uUID) {
        for (LinkedHashSet<UUID> linkedHashSet : FILM_ENTRY_ORDER.values()) {
            linkedHashSet.remove(uUID);
        }
    }
}

