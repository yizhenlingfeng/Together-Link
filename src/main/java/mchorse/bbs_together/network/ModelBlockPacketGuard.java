package mchorse.bbs_together.network;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Vanilla rejects a play packet larger than 8 MiB. A BBS model block carries its full
 * form data inside the block entity NBT, so a large model can produce a
 * BlockEntityUpdateS2CPacket well above that limit and disconnect the client.
 *
 * <p>This guard drops the oversized update instead of letting the encoder throw. The
 * block state is still synced by the normal block update; clients that already have the
 * model keep rendering it.</p>
 */
public final class ModelBlockPacketGuard {
    /** Leave headroom for the packet id, block position and compression envelope. */
    public static final int MAX_NBT_BYTES = 4 * 1024 * 1024;

    private ModelBlockPacketGuard() {}

    public static int nbtSize(BlockEntity entity) {
        try {
            NbtCompound nbt = entity.toInitialChunkDataNbt();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);

            nbt.write(out);
            out.flush();

            return bytes.size();
        }
        catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
