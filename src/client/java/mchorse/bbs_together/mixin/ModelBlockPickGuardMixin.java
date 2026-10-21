package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a locked model block from being copied by pick-block.
 *
 * <p>The previous implementation injected into BBS' {@code ModelBlock.getPickStack}, whose
 * parameter changed from {@code BlockView} to {@code WorldView} in 1.20.4 — a mixin cannot
 * have one descriptor for both versions. {@code MinecraftClient.doItemPick()} has no
 * arguments and the same intermediary name ({@code method_1511}) in 1.20.1 and 1.20.4, so it
 * is the stable place to cancel the local pick action for both versions.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(MinecraftClient.class)
public class ModelBlockPickGuardMixin
{
    @Inject(method = "doItemPick", at = @At("HEAD"), cancellable = true)
    private void bbs$preventLockedPick(CallbackInfo ci)
    {
        MinecraftClient client = (MinecraftClient) (Object) this;

        if (client.world == null || !(client.crosshairTarget instanceof BlockHitResult hit))
        {
            return;
        }

        BlockPos pos = hit.getBlockPos();

        if (!(client.world.getBlockState(pos).getBlock() instanceof ModelBlock))
        {
            return;
        }

        BlockEntity entity = client.world.getBlockEntity(pos);

        if (!(entity instanceof ModelBlockEntity))
        {
            return;
        }

        String id = AssetLockManager.modelBlockId(client.world, pos);

        if (!AssetLockClient.canEdit(AssetLockType.MODEL_BLOCK, id))
        {
            ci.cancel();
        }
    }
}
