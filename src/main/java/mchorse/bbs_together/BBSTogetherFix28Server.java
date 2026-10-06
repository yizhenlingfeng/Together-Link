package mchorse.bbs_together;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_together.BBSTogetherFix28;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public final class BBSTogetherFix28Server {
    private static final ConcurrentHashMap<String, LinkedHashSet<UUID>> FILM_ENTRY_ORDER = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, String> ACTIVE_FILM_EDITORS = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, ServerPacketCrusher> SNAPSHOT_CRUSHERS = new ConcurrentHashMap();
    private static final ConcurrentHashMap<String, Long> RECORDING_BASELINE_ACCEPTED_AT = new ConcurrentHashMap();
    private static final long RECORDING_BASELINE_DEDUP_MS = 2000L;
    private static volatile boolean serverReceiversRegistered;

    private BBSTogetherFix28Server() {
    }

    public static void registerReceivers() {
        if (serverReceiversRegistered) {
            return;
        }
        serverReceiversRegistered = true;
        ServerPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.SERVER_FILM_JOIN_REQUEST, (minecraftServer, class_32222, class_32442, class_25402, packetSender) -> {
            String string = class_25402.readString();
            minecraftServer.execute(() -> BBSTogetherFix28Server.handleJoinRequest(minecraftServer, class_32222, string));
        });
        ServerPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.SERVER_FILM_JOIN_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            ServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new ServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                UUID uUID = class_25402.readUuid();
                String string = class_25402.readString();
                minecraftServer.execute(() -> BBSTogetherFix28Server.handleJoinSnapshot(minecraftServer, uUID, string, byArray));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.SERVER_FILM_LEAVE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            ServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new ServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                minecraftServer.execute(() -> BBSTogetherFix28Server.handleLeaveSnapshot(minecraftServer, class_32222, string, byArray));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.SERVER_FILM_STRUCTURE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            ServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new ServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                minecraftServer.execute(() -> BBSTogetherFix28Server.handleStructureSnapshot(minecraftServer, string, byArray));
            });
        });
        ServerPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.SERVER_RECORDING_BASELINE_SNAPSHOT, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            ServerPacketCrusher serverPacketCrusher = SNAPSHOT_CRUSHERS.computeIfAbsent(class_32222.getUuid(), uUID -> new ServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                String string = class_25402.readString();
                minecraftServer.execute(() -> BBSTogetherFix28Server.handleRecordingBaselineSnapshot(string, byArray));
            });
        });
    }

    public static void resetState() {
        FILM_ENTRY_ORDER.clear();
        ACTIVE_FILM_EDITORS.clear();
        SNAPSHOT_CRUSHERS.clear();
        RECORDING_BASELINE_ACCEPTED_AT.clear();
    }

    private static boolean isServerAlive(MinecraftServer server) {
        return server != null && !server.isStopping() && !server.isStopped();
    }

    private static void safeSaveFilm(String filmId, MapType data) {
        try {
            BBSMod.getFilms().save(filmId, data);
        }
        catch (Exception e) {
            // BBS 2.6 backup may fail for new films; ignore
        }
    }

    private static void handleJoinRequest(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string2) {
        if (!isServerAlive(minecraftServer) || class_32222 == null) {
            return;
        }
        try {
            UUID uUID = class_32222.getUuid();
            BBSTogetherFix28Server.removeFromAllEntryOrders(uUID);
            if (string2 == null || string2.isEmpty()) {
                ACTIVE_FILM_EDITORS.remove(uUID);
                BBSTogetherFix28Server.pruneOffline(minecraftServer);
                return;
            }
            BBSTogetherFix28Server.pruneOffline(minecraftServer);
            ServerPlayerEntity class_32223 = BBSTogetherFix28Server.chooseSnapshotSource(minecraftServer, uUID, string2);
            ACTIVE_FILM_EDITORS.put(uUID, string2);
            FILM_ENTRY_ORDER.computeIfAbsent(string2, string -> new LinkedHashSet()).add(uUID);
            if (class_32223 != null) {
                PacketByteBuf class_25402 = PacketByteBufs.create();
                class_25402.writeUuid(uUID);
                class_25402.writeString(string2);
                ServerPlayNetworking.send((ServerPlayerEntity)class_32223, (Identifier)BBSTogetherFix28.CLIENT_FILM_JOIN_SOURCE_REQUEST, (PacketByteBuf)class_25402);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ServerPlayerEntity chooseSnapshotSource(MinecraftServer minecraftServer, UUID requester, String filmId) {
        ArrayList<ServerPlayerEntity> players = new ArrayList<ServerPlayerEntity>(minecraftServer.getPlayerManager().getPlayerList());
        Map<UUID, String> selections = BBSTogetherFix28Server.getTogetherFilmSelections();
        for (ServerPlayerEntity player : players) {
            UUID playerId = player.getUuid();
            if (playerId.equals(requester) || !filmId.equals(selections.get(playerId)) || !filmId.equals(ACTIVE_FILM_EDITORS.get(playerId))) continue;
            return player;
        }
        LinkedHashSet<UUID> order = FILM_ENTRY_ORDER.get(filmId);
        for (ServerPlayerEntity player : players) {
            UUID playerId = player.getUuid();
            if (playerId.equals(requester) || order == null || !order.contains(playerId) || !filmId.equals(ACTIVE_FILM_EDITORS.get(playerId))) continue;
            return player;
        }
        if (order != null) {
            for (UUID playerId : order) {
                ServerPlayerEntity player;
                if (playerId.equals(requester) || !filmId.equals(ACTIVE_FILM_EDITORS.get(playerId)) || (player = minecraftServer.getPlayerManager().getPlayer(playerId)) == null) continue;
                return player;
            }
        }
        return null;
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

    private static void handleJoinSnapshot(MinecraftServer minecraftServer, UUID uUID, String string, byte[] byArray) {
        if (!isServerAlive(minecraftServer) || uUID == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        try {
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            if (baseType == null || !baseType.isMap()) {
                return;
            }
            MapType mapType = baseType.asMap();
            safeSaveFilm(string, mapType);
            ServerPlayerEntity class_32222 = minecraftServer.getPlayerManager().getPlayer(uUID);
            if (class_32222 != null) {
                TogetherServerNetwork.broadcastFilmSavedToAll(minecraftServer, class_32222, string, mapType);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleLeaveSnapshot(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string, byte[] byArray) {
        if (class_32222 == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        ACTIVE_FILM_EDITORS.remove(class_32222.getUuid(), string);
        if (!isServerAlive(minecraftServer)) {
            return;
        }
        try {
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            if (baseType == null || !baseType.isMap()) {
                return;
            }
            MapType mapType = baseType.asMap();
            safeSaveFilm(string, mapType);
            if (BBSTogetherFix28Server.hasOtherEditor(class_32222.getUuid(), string)) {
                return;
            }
            for (ServerPlayerEntity class_32223 : minecraftServer.getPlayerManager().getPlayerList()) {
                TogetherServerNetwork.broadcastFilmSavedToAll(minecraftServer, class_32223, string, mapType);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleStructureSnapshot(MinecraftServer minecraftServer, String string, byte[] byArray) {
        if (!isServerAlive(minecraftServer) || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        try {
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            if (baseType == null || !baseType.isMap()) {
                return;
            }
            MapType mapType = baseType.asMap();
            Film film = (Film)BBSMod.getFilms().load(string);
            Film film2 = new Film();
            film2.fromData((BaseType)mapType);
            BBSTogetherFix28Server.remapReplaySelections(string, film, film2);
            safeSaveFilm(string, mapType);
            for (ServerPlayerEntity class_32222 : minecraftServer.getPlayerManager().getPlayerList()) {
                TogetherServerNetwork.broadcastFilmSavedToAll(minecraftServer, class_32222, string, mapType);
            }
            TogetherServerNetwork.broadcastReplaySelections(minecraftServer, string);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleRecordingBaselineSnapshot(String string, byte[] byArray) {
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
        safeSaveFilm(string, baseType.asMap());
    }

    private static boolean hasOtherEditor(UUID uUID, String string) {
        for (Map.Entry<UUID, String> entry : ACTIVE_FILM_EDITORS.entrySet()) {
            if (entry.getKey().equals(uUID) || !string.equals(entry.getValue())) continue;
            return true;
        }
        return false;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void remapReplaySelections(String string, Film film, Film film2) {
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
        Map<UUID, String> map2 = BBSTogetherFix28Server.getTogetherFilmSelections();
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
                if (!string.equals(map2.get(uUID))) continue;
                int n = entry.getValue();
                int n2 = BBSTogetherFix28Server.remapReplayIndex(list, list2, n);
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
        String string = BBSTogetherFix28Server.replaySignature(list.get(n));
        boolean[] blArray = new boolean[list2.size()];
        block0: for (n2 = 0; n2 < list.size(); ++n2) {
            if (n2 == n) continue;
            String string2 = BBSTogetherFix28Server.replaySignature(list.get(n2));
            for (int i = 0; i < list2.size(); ++i) {
                if (blArray[i] || !string2.equals(BBSTogetherFix28Server.replaySignature(list2.get(i)))) continue;
                blArray[i] = true;
                continue block0;
            }
        }
        for (n2 = 0; n2 < list2.size(); ++n2) {
            if (blArray[n2] || !string.equals(BBSTogetherFix28Server.replaySignature(list2.get(n2)))) continue;
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

