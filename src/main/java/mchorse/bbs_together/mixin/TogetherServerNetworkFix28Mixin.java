package mchorse.bbs_together.mixin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={TogetherServerNetwork.class}, remap=false)
public class TogetherServerNetworkFix28Mixin {
    @Shadow
    @Final
    private static ConcurrentHashMap<UUID, String> playerEditingFilm;
    @Shadow
    @Final
    private static ConcurrentHashMap<UUID, Object> playerCursors;
    @Shadow
    @Final
    private static LinkedHashMap<UUID, Integer> playerReplaySelections;

    @Shadow
    private static void broadcastFilmSelections(MinecraftServer minecraftServer) {
        throw new AssertionError();
    }

    @Shadow
    public static void broadcastReplaySelections(MinecraftServer minecraftServer, String string) {
        throw new AssertionError();
    }

    @Inject(method={"setup"}, at={@At(value="TAIL")}, remap=false)
    private static void together_fix28Setup(CallbackInfo callbackInfo) {
        BBSTogetherFix28Server.registerReceivers();
    }

    @Inject(method={"reset"}, at={@At(value="TAIL")}, remap=false)
    private static void together_fix28Reset(CallbackInfo callbackInfo) {
        BBSTogetherFix28Server.resetState();
    }

    @Inject(method={"handleCursorPosition"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private static void together_fix155HandleClearCursor(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, PacketByteBuf class_25402, CallbackInfo callbackInfo) {
        int n = class_25402.readerIndex();
        try {
            String string = class_25402.readString();
            int n2 = class_25402.readInt();
            float f = class_25402.readFloat();
            float f2 = class_25402.readFloat();
            int n3 = class_25402.readInt();
            for (int i = 0; i < n3; ++i) {
                class_25402.readInt();
            }
            class_25402.readInt();
            if (string == null || string.isEmpty() || n2 >= 0 || f >= 0.0f || f2 >= 0.0f || n3 > 0) {
                class_25402.readerIndex(n);
                return;
            }
            minecraftServer.execute(() -> {
                UUID uUID = class_32222.getUuid();
                playerEditingFilm.remove(uUID, string);
                playerCursors.remove(uUID);
                TogetherServerNetworkFix28Mixin.broadcastFilmSelections(minecraftServer);
                TogetherServerNetworkFix28Mixin.together_broadcastCursorSnapshot(minecraftServer, string);
            });
            callbackInfo.cancel();
        }
        catch (RuntimeException runtimeException) {
            class_25402.readerIndex(n);
        }
    }

    @Inject(method={"handleReplaySelection"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private static void together_fix156HandleClearReplaySelection(MinecraftServer minecraftServer, ServerPlayerEntity class_32222, PacketByteBuf class_25402, CallbackInfo callbackInfo) {
        int n = class_25402.readerIndex();
        try {
            String string = class_25402.readString();
            int n2 = class_25402.readInt();
            if (string == null || string.isEmpty() || n2 >= 0) {
                class_25402.readerIndex(n);
                return;
            }
            minecraftServer.execute(() -> {
                LinkedHashMap<UUID, Integer> linkedHashMap = playerReplaySelections;
                synchronized (linkedHashMap) {
                    playerReplaySelections.remove(class_32222.getUuid());
                }
                TogetherServerNetworkFix28Mixin.broadcastReplaySelections(minecraftServer, string);
            });
            callbackInfo.cancel();
        }
        catch (RuntimeException runtimeException) {
            class_25402.readerIndex(n);
        }
    }

    @Unique
    private static void together_broadcastCursorSnapshot(MinecraftServer minecraftServer, String string) {
        for (ServerPlayerEntity class_32222 : minecraftServer.getPlayerManager().getPlayerList()) {
            UUID uUID = class_32222.getUuid();
            String string2 = playerEditingFilm.get(uUID);
            if (string2 == null) {
                Object cursorData = playerCursors.get(uUID);
                string2 = TogetherServerNetworkFix28Mixin.together_cursorFilmId(cursorData);
            }
            if (!string.equals(string2)) continue;
            ArrayList<Map.Entry<UUID, Object>> arrayList = new ArrayList<Map.Entry<UUID, Object>>();
            for (Map.Entry<UUID, Object> entry : playerCursors.entrySet()) {
                if (entry.getKey().equals(uUID) || !string.equals(TogetherServerNetworkFix28Mixin.together_cursorFilmId(entry.getValue()))) continue;
                arrayList.add(entry);
            }
            PacketByteBuf class_25402 = PacketByteBufs.create();
            class_25402.writeInt(arrayList.size());
            for (Map.Entry<UUID, Object> entry3 : arrayList) {
                Object v = entry3.getValue();
                List<Integer> list = TogetherServerNetworkFix28Mixin.together_cursorSelected(v);
                class_25402.writeUuid(entry3.getKey());
                class_25402.writeInt(TogetherServerNetworkFix28Mixin.together_cursorInt(v, "editorType"));
                class_25402.writeFloat(TogetherServerNetworkFix28Mixin.together_cursorFloat(v, "tick"));
                class_25402.writeFloat(TogetherServerNetworkFix28Mixin.together_cursorFloat(v, "row"));
                class_25402.writeInt(list.size());
                for (Integer n : list) {
                    class_25402.writeInt(n == null ? -1 : n);
                }
                class_25402.writeInt(TogetherServerNetworkFix28Mixin.together_cursorInt(v, "playheadTick"));
            }
            ServerPlayNetworking.send(class_32222, TogetherServerNetwork.CLIENT_CURSOR_POSITIONS, class_25402);
        }
    }

    @Unique
    private static String together_cursorFilmId(Object object) {
        String string;
        Object object2 = TogetherServerNetworkFix28Mixin.together_cursorField(object, "filmId");
        return object2 instanceof String ? (string = (String)object2) : null;
    }

    @Unique
    private static int together_cursorInt(Object object, String string) {
        int n;
        Object object2 = TogetherServerNetworkFix28Mixin.together_cursorField(object, string);
        if (object2 instanceof Integer) {
            Integer n2 = (Integer)object2;
            n = n2;
        } else {
            n = -1;
        }
        return n;
    }

    @Unique
    private static float together_cursorFloat(Object object, String string) {
        float f;
        Object object2 = TogetherServerNetworkFix28Mixin.together_cursorField(object, string);
        if (object2 instanceof Float) {
            Float f2 = (Float)object2;
            f = f2.floatValue();
        } else {
            f = -1.0f;
        }
        return f;
    }

    @Unique
    private static List<Integer> together_cursorSelected(Object object) {
        List list;
        Object object2 = TogetherServerNetworkFix28Mixin.together_cursorField(object, "selectedIndices");
        return object2 instanceof List ? (list = (List)object2) : List.of();
    }

    @Unique
    private static Object together_cursorField(Object object, String string) {
        if (object == null) {
            return null;
        }
        try {
            Field field = object.getClass().getDeclaredField(string);
            field.setAccessible(true);
            return field.get(object);
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            return null;
        }
    }
}

