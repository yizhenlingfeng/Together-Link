package mchorse.bbs_together.network;

import net.minecraft.util.Identifier;

/**
 * Keeps a single encoded custom packet under the vanilla inbound limit.
 *
 * <p>Minecraft rejects a packet whose payload exceeds {@link #MAX_PAYLOAD} bytes while decoding
 * and drops the connection (the receiver shows "connection lost" with an
 * {@code IllegalArgumentException}). The guard therefore belongs on what actually reaches the
 * encoder: one crusher chunk, which is at most 30,000 bytes of slice plus a 16-byte header
 * (30,016 in total). Applying it to a whole payload before the crusher splits it would reject
 * every legitimate large transfer (asset slices, whole films, manifests) without preventing a
 * single oversize packet.</p>
 */
public final class PacketSizeGuard
{
    /** Vanilla's inbound packet limit ({@code Payload may not be larger than 32767 bytes}). */
    public static final int MAX_PAYLOAD = 32767;

    private PacketSizeGuard()
    {}

    /**
     * Whether one encoded buffer may go to the encoder. Zero is allowed: an empty payload is sent
     * as the crusher's one-byte sentinel and arrives as {@code null} on the other side, which
     * several messages (undo sync, coop decline, non-live commit) rely on to mean "no payload".
     */
    public static boolean fits(int length)
    {
        return length >= 0 && length <= MAX_PAYLOAD;
    }

    /** Logs why a chunk was dropped. Only the crushers' per-chunk send paths should call this. */
    public static void warn(Identifier channel, int length)
    {
        System.out.println("[BBS Together] Dropped oversized chunk for " + channel + " (" + length
            + " bytes > " + MAX_PAYLOAD + "); Minecraft would disconnect the receiver, so it was not sent.");
    }
}
