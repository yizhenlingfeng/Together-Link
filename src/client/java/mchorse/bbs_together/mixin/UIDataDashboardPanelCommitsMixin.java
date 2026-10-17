package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_together.ui.commits.CommitUi;
import mchorse.bbs_together.ui.commits.ICommitsTabHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Teaches the data panel about one id that is not a film: the commit tab. Showing it shows the
 * commit graph instead of loading a film, and the tab bookkeeping must not treat it as "the film
 * that is currently open" (otherwise switching tabs would save the film under the commit tab's id).
 */
@Mixin(UIDataDashboardPanel.class)
public class UIDataDashboardPanelCommitsMixin
{
    @Inject(method = "showTab", at = @At("HEAD"), cancellable = true)
    private void bbs_together$showTab(String id, CallbackInfo ci)
    {
        if (!(((Object) this) instanceof ICommitsTabHost host))
        {
            return;
        }

        if (CommitUi.isCommitsTab(id))
        {
            host.bbs$showCommitsTab();
            ci.cancel();
        }
        else
        {
            host.bbs$hideCommitsTab();
        }
    }

    @Inject(method = "getOpenId", at = @At("HEAD"), cancellable = true)
    private void bbs_together$getOpenId(CallbackInfoReturnable<String> cir)
    {
        if ((((Object) this) instanceof ICommitsTabHost host) && host.bbs$isCommitsTabActive())
        {
            cir.setReturnValue(CommitUi.TAB_ID);
        }
    }

    @Inject(method = "saveOpen", at = @At("HEAD"), cancellable = true)
    private void bbs_together$saveOpen(CallbackInfo ci)
    {
        if ((((Object) this) instanceof ICommitsTabHost host) && host.bbs$isCommitsTabActive())
        {
            ci.cancel();
        }
    }
}
