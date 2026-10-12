package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.BBSTogetherFix28Client;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.sync.SyncBarRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIDashboard.class}, remap=false)
public class UIDashboardMixin {
    @Inject(method={"onOpen"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix164MarkSmallReenter(UIBaseMenu uIBaseMenu, CallbackInfo callbackInfo) {
        if (uIBaseMenu == (Object)this || !TogetherClientNetwork.isConnected()) {
            return;
        }
        BBSTogetherFix28Client.markSmallReenterAppearJoinSuppressed();
    }

    @Inject(method={"onOpen"}, at={@At(value="TAIL")}, remap=false)
    private void onDashboardOpen(UIBaseMenu oldMenu, CallbackInfo ci) {
        if (oldMenu == (Object)this) {
            return;
        }
        if (TogetherClientNetwork.isConnected()) {
            TogetherClientNetwork.sendFilmScreen("open");
            BBSTogetherFix28Client.sendCachedFilmSmallReenter();
        }
    }

    @Inject(method={"onClose"}, at={@At(value="HEAD")}, remap=false)
    private void together_fix131SmallExitSave(UIBaseMenu uIBaseMenu, CallbackInfo callbackInfo) {
        if (uIBaseMenu == (Object)this || !TogetherClientNetwork.isConnected()) {
            return;
        }
        BBSTogetherFix28Client.sendCachedFilmSmallExit();
    }

    @Inject(method={"onClose"}, at={@At(value="TAIL")}, remap=false)
    private void onDashboardClose(UIBaseMenu nextMenu, CallbackInfo ci) {
        if (nextMenu == (Object)this) {
            return;
        }
        if (TogetherClientNetwork.isConnected()) {
            TogetherClientNetwork.sendFilmScreen("");
        }
    }

    @Inject(method={"preRenderMenu"}, at={@At(value="TAIL")}, remap=false)
    private void onPreRenderMenu(UIRenderingContext context, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIDashboard self = (UIDashboard)(Object)this;
        SyncBarRenderer.render(context, self);
        if (TogetherClientNetwork.playersInFilmScreen.isEmpty()) {
            return;
        }
        int padding;
        int x = padding = 8;
        int y = self.height - Icons.LOCKED.h - padding;
        context.batcher.box((float)(x - 3), (float)(y - 3), (float)(x + Icons.LOCKED.w + 3), (float)(y + Icons.LOCKED.h + 3), 0x55000000);
        context.batcher.icon(Icons.LOCKED, -1, (float)x, (float)y);
    }
}

