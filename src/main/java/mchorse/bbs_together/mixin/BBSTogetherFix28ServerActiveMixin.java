package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_together.BBSTogetherFix28;
import mchorse.bbs_together.BBSTogetherFix28Server;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={BBSTogetherFix28Server.class}, remap=false)
public class BBSTogetherFix28ServerActiveMixin {
    @Shadow
    @Final
    private static ConcurrentHashMap<String, LinkedHashSet<UUID>> FILM_ENTRY_ORDER;
    @Shadow
    @Final
    private static ConcurrentHashMap<UUID, ServerPacketCrusher> SNAPSHOT_CRUSHERS;
    @Unique
    private static final ConcurrentHashMap<UUID, String> together_activeFilmEditors;

    @Shadow
    private static Map<UUID, String> getTogetherFilmSelections() {
        throw new AssertionError();
    }

    @Shadow
    private static void removeFromAllEntryOrders(UUID uUID) {
        throw new AssertionError();
    }

    @Overwrite
    public static void resetState() {
        FILM_ENTRY_ORDER.clear();
        together_activeFilmEditors.clear();
        SNAPSHOT_CRUSHERS.clear();
    }

    @Overwrite
    private static void handleJoinRequest(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string2) {
        UUID uUID = class_32222.getUuid();
        BBSTogetherFix28ServerActiveMixin.removeFromAllEntryOrders(uUID);
        if (string2 == null || string2.isEmpty()) {
            together_activeFilmEditors.remove(uUID);
            BBSTogetherFix28ServerActiveMixin.pruneOffline(minecraftServer);
            return;
        }
        BBSTogetherFix28ServerActiveMixin.pruneOffline(minecraftServer);
        ServerPlayerEntity class_32223 = BBSTogetherFix28ServerActiveMixin.chooseSnapshotSource(minecraftServer, uUID, string2);
        together_activeFilmEditors.put(uUID, string2);
        FILM_ENTRY_ORDER.computeIfAbsent(string2, string -> new LinkedHashSet()).add(uUID);
        if (class_32223 != null) {
            PacketByteBuf class_25402 = PacketByteBufs.create();
            class_25402.writeUuid(uUID);
            class_25402.writeString(string2);
            ServerPlayNetworking.send((ServerPlayerEntity)class_32223, (Identifier)BBSTogetherFix28.CLIENT_FILM_JOIN_SOURCE_REQUEST, (PacketByteBuf)class_25402);
        }
    }

    @Overwrite
    private static ServerPlayerEntity chooseSnapshotSource(MinecraftServer minecraftServer, UUID requester, String filmId) {
        ArrayList<ServerPlayerEntity> players = new ArrayList<ServerPlayerEntity>(minecraftServer.getPlayerManager().getPlayerList());
        Map<UUID, String> selections = BBSTogetherFix28ServerActiveMixin.getTogetherFilmSelections();
        for (ServerPlayerEntity player : players) {
            UUID playerId = player.getUuid();
            if (playerId.equals(requester) || !filmId.equals(selections.get(playerId)) || !filmId.equals(together_activeFilmEditors.get(playerId))) continue;
            return player;
        }
        LinkedHashSet<UUID> order = FILM_ENTRY_ORDER.get(filmId);
        for (ServerPlayerEntity player : players) {
            UUID playerId = player.getUuid();
            if (playerId.equals(requester) || order == null || !order.contains(playerId) || !filmId.equals(together_activeFilmEditors.get(playerId))) continue;
            return player;
        }
        if (order != null) {
            for (UUID playerId : order) {
                ServerPlayerEntity player;
                if (playerId.equals(requester) || !filmId.equals(together_activeFilmEditors.get(playerId)) || (player = minecraftServer.getPlayerManager().getPlayer(playerId)) == null) continue;
                return player;
            }
        }
        return null;
    }

    @Overwrite
    private static void handleLeaveSnapshot(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, String string, byte[] byArray) {
        if (class_32222 == null || string == null || string.isEmpty() || byArray == null) {
            return;
        }
        together_activeFilmEditors.remove(class_32222.getUuid(), string);
        BaseType baseType = DataStorageUtils.readFromBytes((byte[])byArray);
        if (baseType == null || !baseType.isMap()) {
            return;
        }
        MapType mapType = baseType.asMap();
        BBSMod.getFilms().save(string, mapType);
        if (BBSTogetherFix28ServerActiveMixin.hasOtherEditor(class_32222.getUuid(), string)) {
            return;
        }
        for (ServerPlayerEntity class_32223 : minecraftServer.getPlayerManager().getPlayerList()) {
            TogetherServerNetwork.broadcastFilmSavedToAll(minecraftServer, class_32223, string, mapType);
        }
    }

    @Overwrite
    private static boolean hasOtherEditor(UUID uUID, String string) {
        for (Map.Entry<UUID, String> entry : together_activeFilmEditors.entrySet()) {
            if (entry.getKey().equals(uUID) || !string.equals(entry.getValue())) continue;
            return true;
        }
        return false;
    }

    @Overwrite
    private static void pruneOffline(MinecraftServer minecraftServer) {
        together_activeFilmEditors.entrySet().removeIf(entry -> minecraftServer.getPlayerManager().getPlayer((UUID)entry.getKey()) == null);
        for (LinkedHashSet<UUID> linkedHashSet : FILM_ENTRY_ORDER.values()) {
            Iterator iterator = linkedHashSet.iterator();
            while (iterator.hasNext()) {
                if (minecraftServer.getPlayerManager().getPlayer((UUID)iterator.next()) != null) continue;
                iterator.remove();
            }
        }
    }

    static {
        together_activeFilmEditors = new ConcurrentHashMap();
    }
}

