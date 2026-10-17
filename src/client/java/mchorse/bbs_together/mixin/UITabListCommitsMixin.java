package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_together.ui.commits.CommitUi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Labels the commit tab like a film tab rather than with its raw pseudo id.
 */
@Mixin(UITabList.class)
public class UITabListCommitsMixin
{
    @Inject(method = "getTabLabel", at = @At("HEAD"), cancellable = true)
    private void bbs_together$getTabLabel(int index, CallbackInfoReturnable<IKey> cir)
    {
        int i = 0;

        for (DataTab tab : (UITabList) (Object) this)
        {
            if (i++ == index && CommitUi.isCommitsTab(tab.dataId))
            {
                cir.setReturnValue(L10n.lang("bbs_together.commits.title"));

                return;
            }
        }
    }
}
