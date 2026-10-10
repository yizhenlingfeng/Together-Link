package mchorse.bbs_together.behaviors;

import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the two halves of the model block recording: the network thread
 * knows which player sent a form packet (and for which block position),
 * while the form actually lands on the server thread inside
 * {@code ModelBlockEntity.updateForm}, where only the block entity is at hand.
 *
 * <p>The network-side mixin leaves the submitting player here, keyed by the
 * block position; the block-entity mixin picks it up and clears it. Stale
 * entries (a form that never landed) expire after a minute.</p>
 */
public class ModelBlockTracker
{
    private static final long EXPIRE_MS = 60_000L;
    private static final Map<BlockPos, Pending> pending = new ConcurrentHashMap<>();

    public static void arm(BlockPos pos, UUID playerId)
    {
        if (pos == null || playerId == null)
        {
            return;
        }

        pending.put(pos.toImmutable(), new Pending(playerId, System.currentTimeMillis()));

        if (pending.size() > 256)
        {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator();

            while (it.hasNext())
            {
                if (now - it.next().getValue().time > EXPIRE_MS)
                {
                    it.remove();
                }
            }
        }
    }

    /** Take the player who most recently submitted a form for this block, if any. */
    public static UUID take(BlockPos pos)
    {
        Pending value = pending.remove(pos);

        return value == null ? null : value.playerId;
    }

    private static class Pending
    {
        final UUID playerId;
        final long time;

        Pending(UUID playerId, long time)
        {
            this.playerId = playerId;
            this.time = time;
        }
    }
}
