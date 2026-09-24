package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.resources.Link;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={BBSModClient.class}, remap=false)
public class BBSClientInitMixin {
    @Inject(method={"onInitializeClient"}, at={@At(value="TAIL")}, remap=false)
    private void onBBSClientInit(CallbackInfo ci) {
        BBSModClient.getL10n().registerOne(lang -> new Link("bbs_together", "strings/" + lang + ".json"));
        BBSModClient.getL10n().reload();
    }
}

