package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.dashboard.panels.bar.UIPanelTopBar;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_together.ui.commits.CommitUi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes room for {@link UIFilmPanelCommitsMixin}'s button: when a top bar carries it, the tab strip
 * starts after the button and ends where the actions begin, exactly like the unmodified bar does.
 */
@Mixin(UIPanelTopBar.class)
public class UIPanelTopBarCommitsMixin
{
    @Shadow private UIDataTabs tabs;

    @Inject(method = "afterResizeApplied", at = @At("TAIL"))
    private void bbs_together$offsetTabs(CallbackInfo ci)
    {
        if (this.tabs == null)
        {
            return;
        }

        UIPanelTopBar bar = (UIPanelTopBar) (Object) this;
        List<CommitUi.UICommitsTopButton> buttons = bar.getChildren(CommitUi.UICommitsTopButton.class);

        if (buttons.isEmpty())
        {
            return;
        }

        /* The button hides itself once its tab exists, and the strip takes the room back. */
        int offset = buttons.get(0).isVisible() ? CommitUi.BUTTON_SIZE : 0;

        this.tabs.x(offset);
        this.tabs.w(1F, -(bar.actions.getContentWidth() + offset));
    }
}
