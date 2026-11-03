package mchorse.bbs_together.compat;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;

/**
 * 1.21.11 overlay: initial chunk NBT now needs a registry lookup.
 */
public final class NbtCompat
{
    private NbtCompat()
    {}

    public static NbtCompound toInitialChunkDataNbt(BlockEntity entity)
    {
        return entity.toInitialChunkDataNbt(entity.getWorld().getRegistryManager());
    }
}
