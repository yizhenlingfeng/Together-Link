package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_together.BBSTogetherFix66Client;
import mchorse.bbs_together.network.CoopClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={BBSModClient.class}, remap=false)
public class BBSClientInitMixin {
    /* HEAD, not TAIL: BBSModClient.onInitializeClient posts RegisterKeybindsEvent halfway
     * through, and our coop recording hotkeys must be registered before that post to make
     * it into keybinds.json. BBSMod.events is a static final field, safe this early. */
    @Inject(method={"onInitializeClient"}, at={@At(value="HEAD")}, remap=false)
    private void onBBSClientInitHead(CallbackInfo ci) {
        mchorse.bbs_mod.BBSMod.events.register(new Object() {
            @mchorse.bbs_mod.api.Subscribe
            public void onRegisterKeybinds(mchorse.bbs_mod.api.client.events.RegisterKeybindsEvent event) {
                event.register(mchorse.bbs_together.network.CoopKeys.class);
            }
        });
    }

    @Inject(method={"onInitializeClient"}, at={@At(value="TAIL")}, remap=false)
    private void onBBSClientInit(CallbackInfo ci) {
        /* The link is registered once through BBSTogetherClient's RegisterL10nEvent.
         * Older builds also called registerOne() here, which loaded every string file
         * twice; only the post-init reload is still needed for addon strings. */
        BBSModClient.getL10n().reload();
        BBSTogetherFix66Client.registerSettingUIs();
    }

    /* Coop recording: the record/replay hotkey's vanilla body requires the film panel to
     * hold a film, but a coop member may be recording with no panel film open — pressing
     * the key would silently do nothing and the take could never be stopped in-world. */
    @Inject(method={"keyRecordReplay"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void together$coopStopTake(CallbackInfo ci) {
        if (!mchorse.bbs_together.network.CoopClient.isCoopTakeActive()) {
            return;
        }
        Recorder recorder = BBSModClient.getFilms().stopRecording();
        if (recorder != null && !recorder.hasNotStarted()) {
            CoopClient.sendTakeDirect(recorder);
        }
        ci.cancel();
    }
}
