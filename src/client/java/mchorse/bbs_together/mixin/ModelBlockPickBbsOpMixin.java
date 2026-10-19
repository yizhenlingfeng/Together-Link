package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops a locked model block from being copied by pick-block. BBS' {@code getPickStack}
 * copies the block entity's whole property/model NBT into the held item, which would let a
 * player who cannot edit the block carry its properties elsewhere and place a duplicate.
 *
 * <p>The normal client does this lookup itself before sending the creative pick packet;
 * returning an empty stack makes the copy a no-op. The server-side break path is gated in
 * {@code BBSTogetherMod} via {@code PlayerBlockBreakEvents}.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = ModelBlock.class, remap = false)
public class ModelBlockPickBbsOpMixin
{
    @Inject(method = "getPickStack", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$preventLockedPick(BlockView world, BlockPos pos, BlockState state, CallbackInfoReturnable<ItemStack> cir)
    {
        if (!(world instanceof World actualWorld) || !actualWorld.isClient())
        {
            return;
        }

        BlockEntity entity = actualWorld.getBlockEntity(pos);

        if (!(entity instanceof ModelBlockEntity))
        {
            return;
        }

        String id = AssetLockManager.modelBlockId(actualWorld, pos);

        if (!AssetLockClient.canEdit(AssetLockType.MODEL_BLOCK, id))
        {
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
