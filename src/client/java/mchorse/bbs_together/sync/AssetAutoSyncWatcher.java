package mchorse.bbs_together.sync;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_together.network.TogetherClientNetwork;

/**
 * Auto sync (client side): watches the local assets folder and nudges the client network
 * to re-diff against the server manifest when files change, so local edits upload without
 * pressing the upload button. Events are debounced by a quiet period; files this client wrote
 * itself are filtered out by {@link SelfWriteGuard} so downloads cannot re-trigger a sync.
 */
@Environment(EnvType.CLIENT)
public class AssetAutoSyncWatcher {
    /** Silence required after the last event before a sync pass fires (editors spam events). */
    private static final long QUIET_MS = 3000L;
    private static final AtomicBoolean started = new AtomicBoolean(false);

    public static void ensureStarted() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(AssetAutoSyncWatcher::run, "BBSTogether-AssetWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        try {
            Path root = BBSMod.getAssetsFolder().toPath();
            WatchService watcher = root.getFileSystem().newWatchService();
            registerAll(root, watcher);
            while (true) {
                boolean dirty;
                try {
                    WatchKey key = watcher.take();
                    dirty = drain(key, watcher);
                    key.reset();
                    dirty |= waitForQuiet(watcher);
                }
                catch (InterruptedException e) {
                    continue;
                }
                if (dirty) {
                    TogetherClientNetwork.onLocalAssetsChanged();
                }
            }
        }
        catch (Exception e) {
            System.out.println("[BBS Assets] Asset watcher died: " + e);
        }
    }

    /** After the first event, wait until nothing happened for {@link #QUIET_MS}. */
    private static boolean waitForQuiet(WatchService watcher) throws InterruptedException {
        long quietStart = System.currentTimeMillis();
        boolean dirty = false;
        while (System.currentTimeMillis() - quietStart < QUIET_MS) {
            long idle = System.currentTimeMillis() - quietStart;
            WatchKey more = watcher.poll(Math.min(QUIET_MS - idle, 500L), TimeUnit.MILLISECONDS);
            if (more != null) {
                dirty |= drain(more, watcher);
                more.reset();
                if (dirty) {
                    quietStart = System.currentTimeMillis();
                }
            }
        }
        return dirty;
    }

    /** Handle one key's events; returns true if any non-temp file was touched. */
    private static boolean drain(WatchKey key, WatchService watcher) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                relevant = true;
                continue;
            }
            Path dir = (Path) key.watchable();
            Object ctx = event.context();
            if (!(ctx instanceof Path)) {
                continue;
            }
            Path child = dir.resolve((Path) ctx);
            String name = child.getFileName() == null ? "" : child.getFileName().toString();
            /* Skip our own in-flight transfer temps; they never become assets. */
            if (name.endsWith(".bbspart") || name.endsWith(".bbsup")) {
                continue;
            }
            /* D1: a file we just wrote ourselves is not a local edit — going through with it would
             * schedule a full manifest round trip for every downloaded asset. */
            if (SelfWriteGuard.isSelfWrite(child)) {
                continue;
            }
            relevant = true;
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                try {
                    if (Files.isDirectory(child)) {
                        registerAll(child, watcher);
                    }
                }
                catch (Exception ignored) {}
            }
        }
        return relevant;
    }

    private static void registerAll(Path root, WatchService watcher) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    try {
                        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    }
                    catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (Exception ignored) {}
    }
}
