package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={MinecraftClient.class})
public class BBSModClientMixin {
    @Inject(method={"method_1572"}, at={@At(value="TAIL")}, remap=false)
    private void onJoinWorld(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                TogetherClientNetwork.sendPlayerColor((Integer)BBSSettings.primaryColor.get());
            }
        });
    }
}

