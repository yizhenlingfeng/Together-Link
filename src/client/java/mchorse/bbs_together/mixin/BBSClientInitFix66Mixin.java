package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_together.BBSTogetherFix66Client;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={BBSModClient.class}, remap=false)
public class BBSClientInitFix66Mixin {
    @Inject(method={"onInitializeClient"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix66RegisterSettingUIs(CallbackInfo callbackInfo) {
        BBSTogetherFix66Client.registerSettingUIs();
    }
}

