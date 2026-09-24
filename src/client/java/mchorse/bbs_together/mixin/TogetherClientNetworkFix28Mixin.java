package mchorse.bbs_together.mixin;

import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.BBSTogetherFixReplayStructure;
import mchorse.bbs_together.network.TogetherClientNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={TogetherClientNetwork.class}, remap=false)
public class TogetherClientNetworkFix28Mixin {
    @Inject(method={"setup"}, at={@At(value="TAIL")}, remap=false)
    private static void together_fix28Setup(CallbackInfo callbackInfo) {
        BBSTogetherFix28Client.registerReceivers();
        BBSTogetherFixReplayStructure.registerClientReceiver();
    }

    @Inject(method={"reset"}, at={@At(value="TAIL")}, remap=false)
    private static void together_fix28Reset(CallbackInfo callbackInfo) {
        BBSTogetherFix28Client.resetState();
        BBSTogetherFixReplayStructure.resetClient();
    }
}

