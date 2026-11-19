package mchorse.bbs_together.network;

import net.minecraft.util.Identifier;

/**
 * Keeps a single custom packet under the vanilla inbound limit.
 *
 * <p>Minecraft rejects any packet whose payload exceeds {@link #MAX_PAYLOAD} bytes while
 * decoding, and drops the connection outright (the client shows "connection lost" with an
 * {@code IllegalArgumentException}). The crushers split a large buffer into chunks, but that
 * split only applies to the buffer itself: a chunk carries 16 bytes of header on top of its
 * slice, and a value handed to the encoder outside the crusher is not split at all. Checking
 * the encoded length here lets the sender drop that one message instead of being disconnected.</p>
 */
public final class PacketSizeGuard
{
    /** Vanilla's inbound packet limit ({@code Payload may not be larger than 32767 bytes}). */
    public static final int MAX_PAYLOAD = 32767;

    private PacketSizeGuard()
    {}

    public static boolean fits(int length)
    {
        return length > 0 && length <= MAX_PAYLOAD;
    }

    /** Logs why a payload was dropped. Used by the crushers' send paths. */
    public static void warn(Identifier channel, int length)
    {
        System.out.println("[BBS Together] Dropped oversized payload for " + channel + " (" + length
            + " bytes > " + MAX_PAYLOAD + "); Minecraft would disconnect the receiver, so it was not sent.");
    }
}
