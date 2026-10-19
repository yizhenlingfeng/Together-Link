package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabsHost;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_together.ui.commits.CommitUi;
import mchorse.bbs_together.ui.commits.ITabListLayout;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tab behaviour for the collaboration UI:
 *
 * <ul>
 *     <li>the commit page is pinned as the first tab, where its top-bar icon used to be;</li>
 *     <li>a film can only be open once — picking a film that already has a tab switches to
 *     that tab instead of opening a duplicate.</li>
 * </ul>
 */
@Mixin(UITabList.class)
public abstract class UITabListCommitsMixin implements ITabListLayout
{
    @Shadow
    @Final
    private List<DataTab> tabs;

    @Shadow
    @Final
    private IUITabsHost host;

    @Shadow
    private int current;

    @Shadow
    public abstract void switchTab(int index);

    @Shadow
    public abstract void addTab();

    @Shadow
    public abstract int findEmptyTab();

    @Shadow
    public abstract void sync();

    /**
     * Reuse the tab that already holds this document. BBS' own {@code pick} replaces the
     * current tab, which is how the same film could end up in several tabs after pressing
     * "new tab" and choosing it again.
     */
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void bbs$reuseOpenTab(String id, CallbackInfo ci)
    {
        if (id == null || id.isEmpty())
        {
            return;
        }

        int existing = this.bbs$indexOf(id);

        if (existing >= 0)
        {
            if (existing != this.current)
            {
                /* Switching saves the current tab before it is left, so an unsaved edit in a
                 * duplicate of the same film is not lost by the cleanup below. */
                this.switchTab(existing);
            }

            this.bbs$removeDuplicates(id);
            this.bbs$removeEmptyTabs();
            ci.cancel();

            return;
        }

        /* The commit page is pinned first; opening a film while it is active must take a new
         * tab rather than replace it. */
        if (this.current >= 0 && this.current < this.tabs.size()
            && CommitUi.isCommitsTab(this.tabs.get(this.current).dataId))
        {
            int empty = this.findEmptyTab();

            if (empty >= 0)
            {
                this.switchTab(empty);
            }
            else
            {
                this.addTab();
            }
        }

        /* Leaving a "new tab" behind is only useful while it is the one being opened into. */
        this.bbs$removeEmptyTabs();
    }

    @Override
    public void bbs$moveTabToFront(String id)
    {
        int index = this.bbs$indexOf(id);

        if (index <= 0)
        {
            return;
        }

        DataTab tab = this.tabs.remove(index);

        this.tabs.add(0, tab);

        if (this.current == index)
        {
            this.current = 0;
        }
        else if (this.current < index)
        {
            this.current += 1;
        }

        this.sync();
    }

    private int bbs$indexOf(String id)
    {
        for (int i = 0; i < this.tabs.size(); i++)
        {
            if (id.equals(this.tabs.get(i).dataId))
            {
                return i;
            }
        }

        return -1;
    }

    /** Remove every other tab holding {@code id}, keeping the current one (and its live data). */
    private void bbs$removeDuplicates(String id)
    {
        for (int i = this.tabs.size() - 1; i >= 0; i--)
        {
            if (i != this.current && id.equals(this.tabs.get(i).dataId))
            {
                this.tabs.remove(i);

                if (i < this.current)
                {
                    this.current -= 1;
                }
            }
        }

        this.sync();
    }

    /** Drop transient "New tab" pages once another film is being shown. */
    private void bbs$removeEmptyTabs()
    {
        for (int i = this.tabs.size() - 1; i >= 0; i--)
        {
            if (i != this.current && this.tabs.get(i).dataId == null)
            {
                this.tabs.remove(i);

                if (i < this.current)
                {
                    this.current -= 1;
                }
            }
        }

        this.sync();
    }

    /**
     * BBS' forced switch (used after removing a tab) still runs the "save the open document"
     * block, but by then the removed tab is gone and {@code current} already points at the
     * neighbour — so it copies the closed document's id into that neighbour's tab. Closing a
     * film tab thus reopened the closed film, and closing the commits tab turned the film tab
     * into a commits tab. The close path has already saved the document before removing it, so
     * the forced switch only needs to show the surviving tab.
     */
    @Inject(method = "switchTab(IZ)V", at = @At("HEAD"), cancellable = true)
    private void bbs$switchAfterClose(int index, boolean force, CallbackInfo ci)
    {
        if (!force || index < 0 || index >= this.tabs.size())
        {
            return;
        }

        this.current = index;
        this.host.openTab(this.tabs.get(index).dataId);
        this.sync();
        ci.cancel();
    }

    @Inject(method = "getTabLabel", at = @At("HEAD"), cancellable = true)
    private void bbs_together$getTabLabel(int index, CallbackInfoReturnable<IKey> cir)
    {
        if (index >= 0 && index < this.tabs.size() && CommitUi.isCommitsTab(this.tabs.get(index).dataId))
        {
            cir.setReturnValue(L10n.lang("bbs_together.commits.title"));
        }
    }
}
