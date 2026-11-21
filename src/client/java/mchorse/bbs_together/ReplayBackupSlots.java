package mchorse.bbs_together;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.utils.manager.storage.CompressedDataStorage;
import mchorse.bbs_together.RemoteSyncApplyDepth;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

public final class ReplayBackupSlots {
    private static final int BACKUP_SLOTS = 10;
    private static final Map<String, Integer> BACKUP_SLOT_BY_FILM = new ConcurrentHashMap<String, Integer>();

    private ReplayBackupSlots() {
    }

    public static void writePeriodicClientBackup(Film film) {
        if (film == null || film.getId() == null || film.getId().isEmpty() || !TogetherClientNetwork.isConnected() || ReplayBackupSlots.isIntegratedHost()) {
            return;
        }
        try {
            File file = ReplayBackupSlots.getBackupFolder(film.getId());
            String string = ReplayBackupSlots.sanitizePathPart(film.getId());
            int n3 = BACKUP_SLOT_BY_FILM.merge(film.getId(), 1, (n, n2) -> (n + 1) % 10);
            File file2 = new File(file, string + "." + n3 + ".dat");
            MapType mapType = film.toData().asMap();
            file.mkdirs();
            new CompressedDataStorage().save(file2, mapType);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public static void broadcastReplayStructureChanged(UIReplayList uIReplayList) {
        if (uIReplayList == null || uIReplayList.panel == null || uIReplayList.panel.getData() == null || !TogetherClientNetwork.isConnected() || RemoteSyncApplyDepth.isApplyingRemoteSync()) {
            return;
        }
        Film film = (Film)uIReplayList.panel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        TogetherClientNetwork.sendSyncDataWithPath(string, Arrays.asList("replays"), (BaseValue)film.replays);
        ReplayBackupSlots.sendCurrentReplaySelection(uIReplayList.panel, film);
    }

    public static void reannounceReplaySelectionAfterFullSync(UIFilmPanel uIFilmPanel) {
        if (uIFilmPanel == null || !uIFilmPanel.isVisible() || uIFilmPanel.getData() == null || !TogetherClientNetwork.isConnected()) {
            return;
        }
        Film film = (Film)uIFilmPanel.getData();
        String string = film.getId();
        if (string == null || string.isEmpty()) {
            return;
        }
        ReplayBackupSlots.sendCurrentReplaySelection(uIFilmPanel, film);
        ReplayBackupSlots.scheduleReplaySelection(uIFilmPanel, string, 150L);
        ReplayBackupSlots.scheduleReplaySelection(uIFilmPanel, string, 450L);
        ReplayBackupSlots.scheduleReplaySelection(uIFilmPanel, string, 900L);
    }

    private static void sendCurrentReplaySelection(UIFilmPanel uIFilmPanel, Film film) {
        if (uIFilmPanel == null || !uIFilmPanel.isVisible() || uIFilmPanel.replayEditor == null || film == null) {
            return;
        }
        Replay replay = uIFilmPanel.replayEditor.getReplay();
        List list = film.replays.getList();
        int n = replay == null ? -1 : list.indexOf(replay);
        TogetherClientNetwork.sendReplaySelection(film.getId(), n);
    }

    private static void scheduleReplaySelection(UIFilmPanel uIFilmPanel, String string, long l) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(l);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            MinecraftClient.getInstance().execute(() -> {
                if (!TogetherClientNetwork.isConnected() || !uIFilmPanel.isVisible() || uIFilmPanel.getData() == null) {
                    return;
                }
                Film film = (Film)uIFilmPanel.getData();
                if (string.equals(film.getId())) {
                    ReplayBackupSlots.sendCurrentReplaySelection(uIFilmPanel, film);
                }
            });
        }, "BBSTogether replay selection reannounce");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isIntegratedHost() {
        return MinecraftClient.getInstance().getServer() != null;
    }

    private static File getBackupFolder(String string) {
        MinecraftClient class_3102 = MinecraftClient.getInstance();
        File file = class_3102.runDirectory;
        String string2 = ReplayBackupSlots.getWorldFolderName(class_3102);
        return new File(new File(new File(file, "saves"), string2), "bbstogether_film_backups/" + ReplayBackupSlots.sanitizePathPart(string));
    }

    private static String getWorldFolderName(MinecraftClient class_3102) {
        String string;
        ServerInfo class_6422 = MinecraftClient.getInstance().getCurrentServerEntry();
        String string2 = string = class_6422 == null ? "" : class_6422.name;
        if (string == null || string.isBlank()) {
            string = "bbstogether_remote";
        }
        return ReplayBackupSlots.sanitizePathPart(string);
    }

    private static String sanitizePathPart(String string) {
        String string2 = string == null ? "" : string.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return string2.isEmpty() ? "unnamed" : string2;
    }
}

