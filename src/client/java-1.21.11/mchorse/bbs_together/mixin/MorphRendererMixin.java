package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.11 moved player rendering to the render-state/collect phase. Suppress the
 * vanilla player render before it is queued, exactly like the old renderPlayer hook.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = MorphRenderer.class, remap = false)
public class MorphRendererMixin
{
    @Inject(method = "collectPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onCollectPlayerHead(AbstractClientPlayerEntity player, MatrixStack matrices, int light,
        int overlay, float tickDelta, LivingEntityRenderState state, CallbackInfoReturnable<Boolean> ci)
    {
        if (!MorphRenderer.hidePlayer)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || player.getUuid().equals(mc.player.getUuid()))
        {
            return;
        }

        String togetherFilmId = TogetherClientNetwork.playersInFilmScreen.get(player.getUuid());

        if (togetherFilmId != null && !togetherFilmId.isEmpty())
        {
            ci.setReturnValue(true);
        }
    }
}
