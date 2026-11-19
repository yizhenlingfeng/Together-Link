package mchorse.bbs_together.network;

/**
 * Keeps a single custom packet under the vanilla inbound limit.
 *
 * <p>Minecraft rejects any packet whose payload exceeds {@link #MAX_PAYLOAD} bytes while
 * decoding, and the offending connection is dropped outright (the client shows "connection
 * lost" with an {@code IllegalArgumentException}). The crushers split a large buffer into
 * chunks, but that split applies to the buffer itself; a chunk carries 16 bytes of header on
 * top of its slice, and a message that is not routed through the crusher's own
 * {@code BUFFER_SIZE} split could still hand the encoder one oversized array. Guarding the
 * encoded length here lets the sender drop that one message instead of being disconnected.</p>
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
}
