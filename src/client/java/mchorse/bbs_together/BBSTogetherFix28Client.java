package mchorse.bbs_together;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.network.ClientPacketCrusher;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_together.BBSTogetherFix28;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;

public final class BBSTogetherFix28Client {
    private static final long CLOSE_SUPPRESS_MS = 5000L;
    private static final long SMALL_REENTER_APPEAR_SKIP_MS = 1500L;
    private static volatile boolean clientReceiversRegistered;
    private static volatile String closeSuppressFilmId;
    private static volatile long closeSuppressUntil;
    private static volatile String lastLeaveSnapshotFilmId;
    private static volatile long lastLeaveSnapshotAt;
    private static volatile String smallReenterAppearSkipFilmId;
    private static volatile long smallReenterAppearSkipUntil;

    private BBSTogetherFix28Client() {
    }

    public static void registerReceivers() {
        if (clientReceiversRegistered) {
            return;
        }
        clientReceiversRegistered = true;
        ClientPlayNetworking.registerGlobalReceiver((Identifier)BBSTogetherFix28.CLIENT_FILM_JOIN_SOURCE_REQUEST, (class_3102, class_6342, class_25402, packetSender) -> {
            UUID uUID = class_25402.readUuid();
            String string = class_25402.readString();
            class_3102.execute(() -> BBSTogetherFix28Client.sendOpenFilmSnapshotTo(uUID, string));
        });
    }

    public static void resetState() {
        closeSuppressFilmId = "";
        closeSuppressUntil = 0L;
        lastLeaveSnapshotFilmId = "";
        lastLeaveSnapshotAt = 0L;
        smallReenterAppearSkipFilmId = "";
        smallReenterAppearSkipUntil = 0L;
    }

    public static void sendFilmJoinRequest(String string) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        String string2 = string == null ? "" : string;
        PacketByteBuf class_25402 = PacketByteBufs.create();
        class_25402.writeString(string2);
        ClientPlayNetworking.send((Identifier)BBSTogetherFix28.SERVER_FILM_JOIN_REQUEST, (PacketByteBuf)class_25402);
    }

    public static void sendFilmLeaveSnapshot(String string, Film film) {
        if (!TogetherClientNetwork.isConnected() || MinecraftClient.getInstance().player == null || string == null || string.isEmpty() || film == null) {
            return;
        }
        long l = System.currentTimeMillis();
        if (string.equals(lastLeaveSnapshotFilmId) && l - lastLeaveSnapshotAt < 500L) {
            return;
        }
        lastLeaveSnapshotFilmId = string;
        lastLeaveSnapshotAt = l;
        try {
            BBSTogetherFix28Client.getClientPacketCrusher().send((PlayerEntity)MinecraftClient.getInstance().player, BBSTogetherFix28.SERVER_FILM_LEAVE_SNAPSHOT, film.toData(), class_25402 -> class_25402.writeString(string));
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
    }

    public static void sendFilmStructureSnapshot(String string, Film film) {
        if (!TogetherClientNetwork.isConnected() || MinecraftClient.getInstance().player == null || string == null || string.isEmpty() || film == null) {
            return;
        }
        try {
            BBSTogetherFix28Client.getClientPacketCrusher().send((PlayerEntity)MinecraftClient.getInstance().player, BBSTogetherFix28.SERVER_FILM_STRUCTURE_SNAPSHOT, film.toData(), class_25402 -> class_25402.writeString(string));
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
    }

    public static void sendRecordingBaselineSnapshot(String string, Film film) {
        if (!TogetherClientNetwork.isConnected() || MinecraftClient.getInstance().player == null || string == null || string.isEmpty() || film == null) {
            return;
        }
        try {
            BBSTogetherFix28Client.getClientPacketCrusher().send((PlayerEntity)MinecraftClient.getInstance().player, BBSTogetherFix28.SERVER_RECORDING_BASELINE_SNAPSHOT, film.toData(), class_25402 -> class_25402.writeString(string));
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
    }

    public static void sendCachedFilmLeaveSnapshot() {
        UIFilmPanel uIFilmPanel = BBSTogetherFix28Client.getOpenFilmPanel();
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        BBSTogetherFix28Client.sendFilmLeaveSnapshot(film.getId(), film);
    }

    public static void sendCachedFilmSmallExit() {
        UIFilmPanel uIFilmPanel = BBSTogetherFix28Client.getOpenFilmPanel();
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        BBSTogetherFix28Client.sendFilmLeaveSnapshot(string, film);
        BBSTogetherFix28Client.markCloseSaveBroadcastSuppressed(string);
        BBSTogetherFix28Client.sendFilmJoinRequest("");
        TogetherClientNetwork.sendReplaySelection(string, -1);
        TogetherClientNetwork.sendCursorPosition(string, -1, -1.0f, -1.0f, Collections.emptyList(), uIFilmPanel.getCursor());
        TogetherClientNetwork.sendFilmSelection("");
    }

    public static void sendCachedFilmSmallReenter() {
        UIFilmPanel uIFilmPanel = BBSTogetherFix28Client.getOpenFilmPanel();
        if (uIFilmPanel == null || !uIFilmPanel.isVisible() || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        TogetherClientNetwork.playerCursors.clear();
        TogetherClientNetwork.sendFilmSelection(string);
        TogetherClientNetwork.sendReplaySelection(string, BBSTogetherFix28Client.getSelectedReplayIndex(uIFilmPanel, film));
        BBSTogetherFix28Client.sendFilmJoinRequest(string);
        BBSTogetherFix28Client.scheduleSmallReenterReplaySelection(uIFilmPanel, string, 150L);
        BBSTogetherFix28Client.scheduleSmallReenterReplaySelection(uIFilmPanel, string, 450L);
        BBSTogetherFix28Client.scheduleSmallReenterReplaySelection(uIFilmPanel, string, 900L);
    }

    public static void markSmallReenterAppearJoinSuppressed() {
        UIFilmPanel uIFilmPanel = BBSTogetherFix28Client.getOpenFilmPanel();
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        smallReenterAppearSkipFilmId = string;
        smallReenterAppearSkipUntil = System.currentTimeMillis() + 1500L;
    }

    public static boolean consumeSmallReenterAppearJoinSuppressed(String string) {
        boolean bl;
        long l = System.currentTimeMillis();
        boolean bl2 = bl = string != null && string.equals(smallReenterAppearSkipFilmId) && l <= smallReenterAppearSkipUntil;
        if (bl || l > smallReenterAppearSkipUntil) {
            smallReenterAppearSkipFilmId = "";
            smallReenterAppearSkipUntil = 0L;
        }
        return bl;
    }

    public static boolean hasOtherEditor(String string) {
        if (string == null || string.isEmpty()) {
            return false;
        }
        UUID uUID = BBSTogetherFix28Client.localPlayerUuid();
        for (Map.Entry<UUID, String> entry : TogetherClientNetwork.filmSelections.entrySet()) {
            if (entry.getKey().equals(uUID) || !string.equals(entry.getValue())) continue;
            return true;
        }
        return false;
    }

    public static void markCloseSaveBroadcastSuppressed(String string) {
        closeSuppressFilmId = string == null ? "" : string;
        closeSuppressUntil = System.currentTimeMillis() + 5000L;
    }

    public static boolean consumeCloseSaveBroadcastSuppressed(String string) {
        boolean bl;
        long l = System.currentTimeMillis();
        boolean bl2 = bl = string != null && string.equals(closeSuppressFilmId) && l <= closeSuppressUntil;
        if (bl || l > closeSuppressUntil) {
            closeSuppressFilmId = "";
            closeSuppressUntil = 0L;
        }
        return bl;
    }

    private static UUID localPlayerUuid() {
        return MinecraftClient.getInstance().player == null ? new UUID(0L, 0L) : MinecraftClient.getInstance().player.getUuid();
    }

    private static void sendOpenFilmSnapshotTo(UUID uUID, String string) {
        UIFilmPanel uIFilmPanel = BBSTogetherFix28Client.getOpenFilmPanel();
        if (uUID == null || string == null || string.isEmpty() || uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        if (!string.equals(film.getId()) || MinecraftClient.getInstance().player == null) {
            return;
        }
        try {
            BBSTogetherFix28Client.getClientPacketCrusher().send((PlayerEntity)MinecraftClient.getInstance().player, BBSTogetherFix28.SERVER_FILM_JOIN_SNAPSHOT, film.toData(), class_25402 -> {
                class_25402.writeUuid(uUID);
                class_25402.writeString(string);
            });
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            reflectiveOperationException.printStackTrace();
        }
    }

    private static UIFilmPanel getOpenFilmPanel() {
        UIDashboard uIDashboard;
        UIDashboard uIDashboard2;
        UIBaseMenu uIBaseMenu = UIScreen.getCurrentMenu();
        UIDashboard uIDashboard3 = uIDashboard2 = uIBaseMenu instanceof UIDashboard ? (uIDashboard = (UIDashboard)uIBaseMenu) : BBSModClient.getDashboardIfCreated();
        if (uIDashboard2 == null) {
            return null;
        }
        return (UIFilmPanel)uIDashboard2.getPanel(UIFilmPanel.class);
    }

    private static int getSelectedReplayIndex(UIFilmPanel uIFilmPanel, Film film) {
        if (uIFilmPanel.replayEditor == null) {
            return -1;
        }
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        return replay == null ? -1 : BBSTogetherFix45.identityReplayIndex(film.replays.getList(), replay);
    }

    private static void scheduleSmallReenterReplaySelection(UIFilmPanel uIFilmPanel, String string, long l) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(l);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            MinecraftClient.getInstance().execute(() -> {
                if (!TogetherClientNetwork.isConnected() || uIFilmPanel == null || !uIFilmPanel.isVisible() || uIFilmPanel.getData() == null) {
                    return;
                }
                Film film = (Film)uIFilmPanel.getData();
                if (string.equals(film.getId())) {
                    TogetherClientNetwork.sendReplaySelection(string, BBSTogetherFix28Client.getSelectedReplayIndex(uIFilmPanel, film));
                }
            });
        }, "BBSTogether small reenter replay selection");
        thread.setDaemon(true);
        thread.start();
    }

    private static ClientPacketCrusher getClientPacketCrusher() throws ReflectiveOperationException {
        Field field = TogetherClientNetwork.class.getDeclaredField("crusher");
        field.setAccessible(true);
        return (ClientPacketCrusher)field.get(null);
    }

    static {
        closeSuppressFilmId = "";
        lastLeaveSnapshotFilmId = "";
        smallReenterAppearSkipFilmId = "";
    }
}

