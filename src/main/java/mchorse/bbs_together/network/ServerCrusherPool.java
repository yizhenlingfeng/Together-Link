package mchorse.bbs_together.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player replacement for BBS' single static {@code ServerNetwork.crusher}.
 *
 * <p>The BBS crusher keys multi-chunk payloads by a client-chosen integer id, and every
 * client starts that id at 0. With one static crusher, two players saving a film at the
 * same time splice their chunks into one buffer. Putting the same crusher behind the
 * sender's UUID keeps the ids isolated without changing BBS' wire format.</p>
 */
public final class ServerCrusherPool {
    private static final Map<UUID, SafeServerPacketCrusher> CRUSHERS = new ConcurrentHashMap<>();

    private ServerCrusherPool() {}

    public static SafeServerPacketCrusher get(UUID playerId) {
        return CRUSHERS.computeIfAbsent(playerId, id -> new SafeServerPacketCrusher());
    }

    public static void remove(UUID playerId) {
        if (playerId != null) {
            CRUSHERS.remove(playerId);
        }
    }

    public static void reset() {
        CRUSHERS.clear();
    }
}
