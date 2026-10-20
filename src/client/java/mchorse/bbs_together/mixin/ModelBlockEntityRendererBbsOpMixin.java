package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.render.ModelBlockLockRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a camera-facing padlock inside the F3 debug outline of a locked model block.
 * The outline itself is BBS' own debug render ({@code options.debugEnabled}); this appends
 * the lock badge at the block centre once that outline is already on screen.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = ModelBlockEntityRenderer.class, remap = false)
public class ModelBlockEntityRendererBbsOpMixin
{
    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void bbs$renderLockBadge(ModelBlockEntity entity, float tickDelta, MatrixStack matrices,
        VertexConsumerProvider vertexConsumers, int light, int overlay, CallbackInfo ci)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!client.options.debugEnabled || !TogetherClientNetwork.isConnected())
        {
            return;
        }

        String id = AssetLockManager.modelBlockId(entity.getWorld(), entity.getPos());

        if (AssetLockClient.get(AssetLockType.MODEL_BLOCK, id) == null)
        {
            return;
        }

        if (!AssetLockClient.isLockedForSelf(AssetLockType.MODEL_BLOCK, id) && !AssetLockClient.canManage())
        {
            return;
        }

        ModelBlockLockRenderer.render(matrices, client.gameRenderer.getCamera());
    }
}
