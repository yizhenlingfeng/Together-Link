package mchorse.bbs_together;

import mchorse.bbs_together.network.TogetherClientNetworking;

import mchorse.bbs_together.network.TogetherServerNetworking;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_mod.settings.values.core.StableIds;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_together.RemoteSyncApplyDepth;
import mchorse.bbs_together.ReplayIndexes;
import mchorse.bbs_together.network.CompressedClientPacketCrusher;
import mchorse.bbs_together.network.CompressedServerPacketCrusher;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

public final class ReplayStructureRecovery {
    private static final Identifier SERVER_REPLAY_STRUCTURE = Identifier.of("bbs_together", "s_replay_structure_fix1615");
    private static final Identifier CLIENT_REPLAY_STRUCTURE = Identifier.of("bbs_together", "c_replay_structure_fix1615");
    /* Use Together's compressed crushers so 1.21.11 payload channels go through
     * TogetherPayload instead of BBS's unregistered ServerNetwork.BufPayload. */
    private static final ClientPacketCrusher CLIENT_CRUSHER = new CompressedClientPacketCrusher();
    private static final ConcurrentHashMap<UUID, ServerPacketCrusher> SERVER_SENDERS = new ConcurrentHashMap();
    private static final ConcurrentHashMap<UUID, ServerPacketCrusher> SERVER_RECEIVERS = new ConcurrentHashMap();
    private static final ClientPacketCrusher CLIENT_RECEIVER = new CompressedClientPacketCrusher();
    private static final ConcurrentHashMap<String, Boolean> CLIENT_STRUCTURE_APPLY = new ConcurrentHashMap();
    private static final int MAX_STRUCTURE_REPLAYS = 4096;
    private static boolean clientRegistered;
    private static boolean serverRegistered;

    private ReplayStructureRecovery() {
    }

    public static Identifier getServerReplayStructureChannel() {
        return SERVER_REPLAY_STRUCTURE;
    }

    public static Identifier getClientReplayStructureChannel() {
        return CLIENT_REPLAY_STRUCTURE;
    }

    public static List<Replay> capture(UIReplayList uIReplayList) {
        if (uIReplayList == null || uIReplayList.panel == null || uIReplayList.panel.getData() == null) {
            return null;
        }
        return new ArrayList<Replay>(((Film)uIReplayList.panel.getData()).replays.getList());
    }

    public static void sendIfChanged(UIReplayList uIReplayList, List<Replay> list) {
        if (uIReplayList == null || list == null || uIReplayList.panel == null || uIReplayList.panel.getData() == null || !TogetherClientNetwork.isConnected() || RemoteSyncApplyDepth.isApplyingRemoteSync()) {
            return;
        }
        Film film = (Film)uIReplayList.panel.getData();
        List list2 = film.replays.getList();
        int[] nArray = ReplayStructureRecovery.buildIdentityMapping(list, list2);
        if (!ReplayStructureRecovery.hasStructuralChange(nArray, list.size(), list2.size())) {
            return;
        }
        String string = film.getId();
        MinecraftClient class_3102 = MinecraftClient.getInstance();
        if (string == null || string.isEmpty() || class_3102.player == null) {
            return;
        }
        int n = ReplayIndexes.currentReplayIndex(uIReplayList.panel, film);
        CLIENT_CRUSHER.send((PlayerEntity)class_3102.player, SERVER_REPLAY_STRUCTURE, film.replays.toData(), class_25402 -> ReplayStructureRecovery.writeMetadata(class_25402, string, list.size(), list2.size(), nArray, n));
        TogetherClientNetwork.sendReplaySelection(string, n);
    }

    public static void registerClientReceiver() {
        if (clientRegistered) {
            return;
        }
        clientRegistered = true;
        TogetherClientNetworking.register((Identifier)CLIENT_REPLAY_STRUCTURE, (class_3102, class_6342, class_25403, packetSender) -> CLIENT_RECEIVER.receive(class_25403, (byArray, class_25402) -> {
            UUID uUID = class_25402.readUuid();
            Metadata metadata = ReplayStructureRecovery.readMetadata(class_25402);
            BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
            class_3102.execute(() -> ReplayStructureRecovery.applyClientStructure(uUID, metadata, baseType));
        }));
    }

    public static void registerServerReceiver() {
        if (serverRegistered) {
            return;
        }
        serverRegistered = true;
        TogetherServerNetworking.register((Identifier)SERVER_REPLAY_STRUCTURE, (minecraftServer, class_32222, class_32442, class_25403, packetSender) -> {
            ServerPacketCrusher serverPacketCrusher = SERVER_RECEIVERS.computeIfAbsent(class_32222.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.receive(class_25403, (byArray, class_25402) -> {
                Metadata metadata = ReplayStructureRecovery.readMetadata(class_25402);
                BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
                minecraftServer.execute(() -> ReplayStructureRecovery.applyServerStructure(minecraftServer, class_32222, metadata, baseType));
            });
        });
    }

    public static void resetClient() {
        CLIENT_CRUSHER.reset();
        CLIENT_RECEIVER.reset();
        CLIENT_STRUCTURE_APPLY.clear();
    }

    public static void resetServer() {
        SERVER_SENDERS.clear();
        SERVER_RECEIVERS.clear();
    }

    private static void applyServerStructure(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, Metadata metadata, BaseType baseType) {
        if (minecraftServer == null || class_32222 == null || metadata == null || baseType == null || metadata.filmId.isEmpty()) {
            return;
        }
        if (!ReplayStructureRecovery.isUsableStructure(metadata, baseType)) {
            System.out.println("[BBSTogether] Dropped replay structure film=" + metadata.filmId + " player=" + class_32222.getUuid() + " old=" + metadata.oldSize + " new=" + metadata.newSize + " payload=" + (baseType.isList() ? baseType.asList().size() : "not-a-list"));
            return;
        }
        Film film = (Film)BBSMod.getFilms().load(metadata.filmId);
        if (film == null) {
            return;
        }
        int n = film.replays.getList().size();
        try {
            if (n == metadata.oldSize) {
                ReplayStructureRecovery.applyMappedReplayStructure(film, metadata, baseType);
            } else {
                film.replays.fromData(baseType);
            }
        }
        catch (Throwable throwable) {
            System.out.println("[BBSTogether] Failed to apply replay structure film=" + metadata.filmId + " old=" + metadata.oldSize + " new=" + metadata.newSize + " server=" + n + ": " + throwable);
            throwable.printStackTrace();
            return;
        }
        System.out.println("[BBSTogether] Applied replay structure film=" + metadata.filmId + " old=" + metadata.oldSize + " new=" + metadata.newSize + " server=" + n);
        BBSMod.getFilms().save(metadata.filmId, film.toData().asMap());
        ReplayStructureRecovery.remapServerSelections(class_32222.getUuid(), metadata, n);
        for (ServerPlayerEntity class_32223 : minecraftServer.getPlayerManager().getPlayerList()) {
            if (class_32223 == class_32222) continue;
            ServerPacketCrusher serverPacketCrusher = SERVER_SENDERS.computeIfAbsent(class_32223.getUuid(), uUID -> new CompressedServerPacketCrusher());
            serverPacketCrusher.send((PlayerEntity)class_32223, CLIENT_REPLAY_STRUCTURE, baseType, class_25402 -> {
                class_25402.writeUuid(class_32222.getUuid());
                ReplayStructureRecovery.writeMetadata(class_25402, metadata.filmId, metadata.oldSize, metadata.newSize, metadata.oldToNew, metadata.selectedIndex);
            });
        }
        TogetherServerNetwork.broadcastReplaySelections(minecraftServer, metadata.filmId);
    }

    private static void applyClientStructure(UUID uUID, Metadata metadata, BaseType baseType) {
        if (metadata == null || baseType == null || metadata.filmId.isEmpty()) {
            return;
        }
        if (!ReplayStructureRecovery.isUsableStructure(metadata, baseType)) {
            System.out.println("[BBSTogether] Dropped remote replay structure film=" + metadata.filmId + " old=" + metadata.oldSize + " new=" + metadata.newSize + " payload=" + (baseType.isList() ? baseType.asList().size() : "not-a-list"));
            return;
        }
        if (CLIENT_STRUCTURE_APPLY.putIfAbsent(metadata.filmId, Boolean.TRUE) != null) {
            return;
        }
        try {
            ReplayStructureRecovery.applyClientStructureNow(uUID, metadata, baseType);
        }
        finally {
            CLIENT_STRUCTURE_APPLY.remove(metadata.filmId);
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void applyClientStructureNow(UUID uUID, Metadata metadata, BaseType baseType) {
        UIFilmPanel uIFilmPanel;
        UIDashboard uIDashboard = BBSModClient.getDashboardIfCreated();
        UIFilmPanel uIFilmPanel2 = uIFilmPanel = uIDashboard == null ? null : (UIFilmPanel)uIDashboard.getPanel(UIFilmPanel.class);
        if (uIFilmPanel == null || uIFilmPanel.getData() == null || !metadata.filmId.equals(((Film)uIFilmPanel.getData()).getId())) {
            ReplayStructureRecovery.saveHiddenFilmStructure(metadata, baseType);
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        int n = film.replays.getList().size();
        int n2 = ReplayIndexes.currentReplayIndex(uIFilmPanel, film);
        boolean bl = n == metadata.oldSize;
        RemoteSyncApplyDepth.beginRemoteSyncApply();
        try {
            try {
                if (bl) {
                    ReplayStructureRecovery.applyMappedReplayStructure(film, metadata, baseType);
                } else {
                    film.replays.fromData(baseType);
                }
            }
            catch (Throwable throwable) {
                System.out.println("[BBSTogether] Failed to apply remote replay structure film=" + metadata.filmId + " old=" + metadata.oldSize + " new=" + metadata.newSize + " local=" + n + ": " + throwable);
                throwable.printStackTrace();
            }
            List list = film.replays.getList();
            int n3 = bl ? ReplayStructureRecovery.remapIndex(n2, metadata.oldToNew, list.size()) : ReplayStructureRecovery.clampIndex(n2, list.size());
            ReplayStructureRecovery.remapClientSelections(metadata, uUID);
            if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
                uIFilmPanel.replayEditor.replaysList.replays.refreshReplayList();
                uIFilmPanel.replayEditor.replaysList.replays.update();
            }
            uIFilmPanel.getController().createEntities();
            if (n3 < 0) {
                uIFilmPanel.replayEditor.setReplay(null);
            } else {
                Replay replay = (Replay)list.get(n3);
                TogetherClientNetwork.together_bindReplayKeepingCamera(uIFilmPanel.replayEditor, replay);
                uIFilmPanel.replayEditor.updateChannelsList();
                if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
                    uIFilmPanel.replayEditor.replaysList.replays.scrollToReplay(replay);
                }
            }
            TogetherClientNetwork.sendReplaySelection(metadata.filmId, n3);
        }
        finally {
            RemoteSyncApplyDepth.endRemoteSyncApply();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void remapServerSelections(UUID uUID, Metadata metadata, int n) {
        Map<UUID, Integer> map = ReplayStructureRecovery.getServerReplaySelections();
        Map<UUID, String> map2 = ReplayStructureRecovery.getServerFilmSelections();
        if (map == null) {
            return;
        }
        Map<UUID, Integer> map3 = map;
        synchronized (map3) {
            for (Map.Entry<UUID, Integer> entry : new ArrayList<Map.Entry<UUID, Integer>>(map.entrySet())) {
                UUID uUID2 = entry.getKey();
                if (!metadata.filmId.equals(map2.get(uUID2))) continue;
                int n2 = uUID2.equals(uUID) ? ReplayStructureRecovery.clampIndex(metadata.selectedIndex, metadata.newSize) : (n == metadata.oldSize ? ReplayStructureRecovery.remapIndex(entry.getValue(), metadata.oldToNew, metadata.newSize) : ReplayStructureRecovery.clampIndex(entry.getValue(), metadata.newSize));
                map.remove(uUID2);
                if (n2 < 0) continue;
                map.put(uUID2, n2);
            }
            if (!map.containsKey(uUID) && metadata.selectedIndex >= 0) {
                map.put(uUID, ReplayStructureRecovery.clampIndex(metadata.selectedIndex, metadata.newSize));
            }
        }
    }

    private static void remapClientSelections(Metadata metadata, UUID uUID) {
        Map<Integer, List<UUID>> map = TogetherClientNetwork.replaySelections.get(metadata.filmId);
        if (map == null) {
            map = new HashMap<Integer, List<UUID>>();
        }
        HashMap<Integer, List<UUID>> hashMap = new HashMap<Integer, List<UUID>>();
        for (Map.Entry<Integer, List<UUID>> entry2 : map.entrySet()) {
            int n2 = ReplayStructureRecovery.remapIndex(entry2.getKey(), metadata.oldToNew, metadata.newSize);
            if (n2 < 0) continue;
            hashMap.computeIfAbsent(n2, n -> new ArrayList()).addAll((Collection)entry2.getValue());
        }
        if (uUID != null) {
            for (Map.Entry<Integer, List<UUID>> entry2 : hashMap.entrySet()) {
                entry2.getValue().remove(uUID);
            }
            hashMap.entrySet().removeIf(entry -> ((List)entry.getValue()).isEmpty());
            int n3 = ReplayStructureRecovery.clampIndex(metadata.selectedIndex, metadata.newSize);
            if (n3 >= 0) {
                hashMap.computeIfAbsent(n3, n -> new ArrayList()).add(uUID);
            }
        }
        TogetherClientNetwork.replaySelections.put(metadata.filmId, hashMap);
    }

    private static int[] buildIdentityMapping(List<Replay> list, List<Replay> list2) {
        int[] nArray = new int[list.size()];
        for (int i = 0; i < list.size(); ++i) {
            nArray[i] = ReplayStructureRecovery.identityIndex(list2, list.get(i));
        }
        return nArray;
    }

    private static int identityIndex(List<Replay> list, Replay replay) {
        for (int i = 0; i < list.size(); ++i) {
            if (list.get(i) != replay) continue;
            return i;
        }
        return -1;
    }

    private static boolean hasStructuralChange(int[] nArray, int n, int n2) {
        if (n != n2) {
            return true;
        }
        for (int i = 0; i < nArray.length; ++i) {
            if (nArray[i] == i) continue;
            return true;
        }
        return false;
    }

    private static int remapIndex(int n, int[] nArray, int n2) {
        int n3;
        if (n < 0 || n2 <= 0) {
            return -1;
        }
        if (nArray == null || n >= nArray.length) {
            return ReplayStructureRecovery.clampIndex(n, n2);
        }
        int n4 = nArray[n];
        if (n4 >= 0 && n4 < n2) {
            return n4;
        }
        for (n3 = n + 1; n3 < nArray.length; ++n3) {
            if (nArray[n3] < 0 || nArray[n3] >= n2) continue;
            return nArray[n3];
        }
        for (n3 = n - 1; n3 >= 0; --n3) {
            if (nArray[n3] < 0 || nArray[n3] >= n2) continue;
            return nArray[n3];
        }
        return 0;
    }

    private static int clampIndex(int n, int n2) {
        return n2 <= 0 || n < 0 ? -1 : Math.min(n, n2 - 1);
    }

    /**
     * Rebuilds the list around the replays that survived, then fills the new slots from the
     * payload. Replays are a {@code ValueStableList}, so the ids must be taken from the payload
     * before insertion: {@code add()} would otherwise mint a random id that differs between the
     * server and each client and break every id-addressed delta for that replay. Building the new
     * elements before touching the list keeps a malformed entry from half replacing the film.
     */
    private static void applyMappedReplayStructure(Film film, Metadata metadata, BaseType baseType) {
        if (film == null || !ReplayStructureRecovery.isUsableStructure(metadata, baseType)) {
            return;
        }
        ArrayList arrayList = new ArrayList(film.replays.getList());
        ListType listType = baseType.asList();
        Replay[] replayArray = new Replay[metadata.newSize];
        for (int i = 0; i < metadata.oldToNew.length && i < arrayList.size(); ++i) {
            int n2 = metadata.oldToNew[i];
            if (n2 < 0 || n2 >= replayArray.length || replayArray[n2] != null) continue;
            replayArray[n2] = (Replay)arrayList.get(i);
        }
        for (int i = 0; i < replayArray.length; ++i) {
            if (replayArray[i] != null) continue;
            BaseType item = listType.get(i);
            Replay replay = new Replay(String.valueOf(i));
            replay.fromData(item);

            if (item.isMap() && StableIds.isStableId(item.asMap().getString(StableIds.KEY))) {
                replay.setId(item.asMap().getString(StableIds.KEY));
            }
            replayArray[i] = replay;
        }
        List list = film.replays.getAllTyped();
        list.clear();
        for (Replay replay : replayArray) {
            film.replays.add(replay);
        }
    }

    /**
     * {@code ValueStableList#fromData} clears the list before it checks the payload type, so a
     * payload that does not carry the promised replays is how a film ends up with none.
     */
    private static boolean isUsableStructure(Metadata metadata, BaseType baseType) {
        if (metadata == null || baseType == null || !baseType.isList()) {
            return false;
        }
        if (metadata.newSize < 0 || metadata.newSize > MAX_STRUCTURE_REPLAYS) {
            return false;
        }
        return baseType.asList().size() == metadata.newSize;
    }

    private static void saveHiddenFilmStructure(Metadata metadata, BaseType baseType) {
        Film film = (Film)BBSMod.getFilms().load(metadata.filmId);
        if (film == null) {
            return;
        }
        int n = film.replays.getList().size();
        try {
            if (n == metadata.oldSize) {
                ReplayStructureRecovery.applyMappedReplayStructure(film, metadata, baseType);
            } else {
                film.replays.fromData(baseType);
            }
        }
        catch (Throwable throwable) {
            System.out.println("[BBSTogether] Failed to apply replay structure film=" + metadata.filmId + " old=" + metadata.oldSize + " new=" + metadata.newSize + " stored=" + n + ": " + throwable);
            throwable.printStackTrace();
            return;
        }
        BBSMod.getFilms().save(metadata.filmId, film.toData().asMap());
        try {
            ContentType.FILMS.getRepository().save(metadata.filmId, film.toData().asMap());
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private static void writeMetadata(PacketByteBuf class_25402, String string, int n, int n2, int[] nArray, int n3) {
        class_25402.writeString(string);
        class_25402.writeInt(n);
        class_25402.writeInt(n2);
        class_25402.writeInt(nArray.length);
        for (int n4 : nArray) {
            class_25402.writeInt(n4);
        }
        class_25402.writeInt(n3);
    }

    private static Metadata readMetadata(PacketByteBuf class_25402) {
        String string = class_25402.readString();
        int n = class_25402.readInt();
        int n2 = class_25402.readInt();
        int n3 = class_25402.readInt();
        if (n3 < 0 || n3 > 100000) {
            throw new IllegalArgumentException("Invalid replay index mapping length: " + n3);
        }
        int[] nArray = new int[n3];
        for (int i = 0; i < n3; ++i) {
            nArray[i] = class_25402.readInt();
        }
        return new Metadata(string, n, n2, nArray, class_25402.readInt());
    }

    private static Map<UUID, Integer> getServerReplaySelections() {
        return (Map)ReplayStructureRecovery.getServerField("playerReplaySelections", null);
    }

    private static Map<UUID, String> getServerFilmSelections() {
        Map map = (Map)ReplayStructureRecovery.getServerField("playerEditingFilm", Map.of());
        return map == null ? Map.of() : map;
    }

    private static Object getServerField(String string, Object object) {
        try {
            Field field = TogetherServerNetwork.class.getDeclaredField(string);
            field.setAccessible(true);
            return field.get(null);
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
            return object;
        }
    }

    private record Metadata(String filmId, int oldSize, int newSize, int[] oldToNew, int selectedIndex) {
    }
}

