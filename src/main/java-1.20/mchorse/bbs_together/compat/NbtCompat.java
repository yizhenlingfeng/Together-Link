package mchorse.bbs_together.compat;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;

/**
 * 1.20 block-entity initial chunk NBT bridge. The 1.21.11 overlay passes the
 * registry lookup that the new method requires.
 */
public final class NbtCompat
{
    private NbtCompat()
    {}

    public static NbtCompound toInitialChunkDataNbt(BlockEntity entity)
    {
        return entity.toInitialChunkDataNbt();
    }
}
