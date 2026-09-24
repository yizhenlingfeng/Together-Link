package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.network.TogetherClientNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={UIDashboard.class}, remap=false)
public class UIDashboardSmallExitSaveMixin {
    @Inject(method={"onClose"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix131SmallExitSave(UIBaseMenu uIBaseMenu, CallbackInfo callbackInfo) {
        if (uIBaseMenu == (Object)this || !TogetherClientNetwork.isConnected()) {
            return;
        }
        BBSTogetherFix28Client.sendCachedFilmSmallExit();
    }

    @Inject(method={"onOpen"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix164MarkSmallReenter(UIBaseMenu uIBaseMenu, CallbackInfo callbackInfo) {
        if (uIBaseMenu == (Object)this || !TogetherClientNetwork.isConnected()) {
            return;
        }
        BBSTogetherFix28Client.markSmallReenterAppearJoinSuppressed();
    }

    @Inject(method={"onOpen"}, at={@At(value="TAIL")}, remap=false)
    private void together_fix154SmallReenter(UIBaseMenu uIBaseMenu, CallbackInfo callbackInfo) {
        if (uIBaseMenu == (Object)this || !TogetherClientNetwork.isConnected()) {
            return;
        }
        BBSTogetherFix28Client.sendCachedFilmSmallReenter();
    }
}

