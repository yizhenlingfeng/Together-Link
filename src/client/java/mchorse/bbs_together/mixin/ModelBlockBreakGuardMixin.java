package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.locks.ModelBlockBreakHint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the local block-breaking prediction for a model block the lock denies. Without
 * this the client removes the block and sends the break packet every time the player
 * holds the attack key; the server denies it and has to resend the whole block entity,
 * which makes the model flicker and produces repeated NBT traffic.
 *
 * <p>This is only a local guard. A modified client can still send the packet; the server
 * keeps its own {@code PlayerBlockBreakEvents} gate and resyncs on cancellation.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerInteractionManager.class)
public class ModelBlockBreakGuardMixin
{
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void bbs$blockLockedAttack(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (bbs$isLocked(pos))
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void bbs$blockLockedProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir)
    {
        if (bbs$isLocked(pos))
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void bbs$blockLockedBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir)
    {
        if (bbs$isLocked(pos))
        {
            cir.setReturnValue(false);
        }
    }

    private static boolean bbs$isLocked(BlockPos pos)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (pos == null || client.world == null || client.player == null)
        {
            return false;
        }

        if (!(client.world.getBlockState(pos).getBlock() instanceof ModelBlock))
        {
            return false;
        }

        String id = AssetLockManager.modelBlockId(client.world, pos);

        if (AssetLockClient.canEdit(AssetLockType.MODEL_BLOCK, id))
        {
            return false;
        }

        ModelBlockBreakHint.showLocked();

        return true;
    }
}
