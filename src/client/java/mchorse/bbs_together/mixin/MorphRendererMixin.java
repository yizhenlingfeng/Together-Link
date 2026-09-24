package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(value=EnvType.CLIENT)
@Mixin(value={MorphRenderer.class}, remap=false)
public class MorphRendererMixin {
    @Inject(method={"renderPlayer"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private static void onRenderPlayerHead(AbstractClientPlayerEntity player, float f, float g, MatrixStack matrixStack, VertexConsumerProvider vertexConsumerProvider, int light, CallbackInfoReturnable<Boolean> ci) {
        if (!MorphRenderer.hidePlayer) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }
        if (player.getUuid().equals(mc.player.getUuid())) {
            return;
        }
        String togetherFilmId = TogetherClientNetwork.filmSelections.get(player.getUuid());
        if (togetherFilmId != null && !togetherFilmId.isEmpty()) {
            ci.setReturnValue(true);
        }
    }
}