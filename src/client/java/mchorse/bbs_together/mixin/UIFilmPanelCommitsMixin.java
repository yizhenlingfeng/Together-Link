package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIEditorDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.bar.UIPanelTopBar;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_together.ui.commits.CommitUi;
import mchorse.bbs_together.ui.commits.ICommitsTabHost;
import mchorse.bbs_together.ui.commits.ITabListLayout;
import mchorse.bbs_together.ui.commits.UICommitsPanel;
import mchorse.bbs_together.ui.commits.UIFilmCommitsView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/**
 * Wires the commit graph into the film editor in the two places the graph belongs:
 *
 * <ul>
 *     <li>a dock panel, initially stacked with the Properties panel, that shows the commits of
 *     the film currently open in the editor and can be repositioned once the layout is unlocked;</li>
 *     <li>a dashboard-style tab of the film editor itself, next to the film tabs, that shows the
 *     commits of every film. It replaces the editor content while active while the film tabs stay
 *     visible.</li>
 * </ul>
 */
@Mixin(UIFilmPanel.class)
public class UIFilmPanelCommitsMixin implements ICommitsTabHost
{
    @Shadow
    private EditorLayoutNode ensureFilmLayoutPanels(EditorLayoutNode root)
    {
        return root;
    }

    @Unique
    private UICommitsPanel bbs$commitsPage;

    @Unique
    private boolean bbs$commitsTab;

    /* Dock panel, created before the layout is mounted so the layout can place it */

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/ui/framework/elements/layout/UIDockLayout;mount()V"))
    private void bbs$addDockPanel(CallbackInfo ci)
    {
        UIFilmPanel panel = (UIFilmPanel) (Object) this;

        panel.dock.addPanel(CommitUi.DOCK_PANEL_ID, new UIFilmCommitsView(() -> {
            Film film = panel.getData();

            return film == null ? "" : film.getId();
        }), Icons.GRAPH, L10n.lang("bbs_together.commits.tab"));

        panel.dock.ensure(this::bbs$ensureCommitsStack);
    }

    /**
     * Adds the commit view next to the editor group ("Properties", i.e. editArea) the first time
     * a layout without it is loaded. Once it is anywhere in the layout it is left alone: the dock
     * lock can be turned off and the user can drag the commit pane out of the stack, resize it or
     * place it in another column without this callback pulling it back on the next layout pass.
     */
    @Unique
    private EditorLayoutNode bbs$ensureCommitsStack(EditorLayoutNode root)
    {
        EditorLayoutNode out = this.ensureFilmLayoutPanels(root);
        Set<String> ids = new HashSet<String>();

        EditorLayoutNode.collectPanelIds(out, ids);

        if (!ids.contains("editArea") || ids.contains(CommitUi.DOCK_PANEL_ID))
        {
            return out;
        }

        EditorLayoutNode stacked = EditorLayoutNode.copyWithInsertStackAt(out, "editArea", CommitUi.DOCK_PANEL_ID);

        return EditorLayoutNode.copyWithStackActivePanel(stacked, CommitUi.DOCK_PANEL_ID, "editArea");
    }

    /* Top bar button and the commits page, created once the panel is fully built */

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbs$addTopButton(CallbackInfo ci)
    {
        UIFilmPanel panel = (UIFilmPanel) (Object) this;
        UIDashboard dashboard = ((UIDashboardPanel) (Object) this).dashboard;

        if (panel.topBar.getChildren(CommitUi.UICommitsTopButton.class).isEmpty())
        {
            CommitUi.UICommitsTopButton button = new CommitUi.UICommitsTopButton((b) -> CommitUi.openFilm(""));

            button.relative(panel.topBar).x(0).y(0).wh(CommitUi.BUTTON_SIZE, CommitUi.BUTTON_SIZE);
            button.tooltip(L10n.lang("bbs_together.commits.button"), Direction.BOTTOM);

            panel.topBar.add(button);
            panel.topBar.resize();
        }

        this.bbs$commitsPage = new UICommitsPanel(dashboard);
        this.bbs$commitsPage.relative((UIElement) (Object) this).x(0).y(UIPanelTopBar.HEIGHT).w(1F).h(1F, -UIPanelTopBar.HEIGHT);
        this.bbs$commitsPage.setVisible(false);

        ((UIElement) (Object) this).add(this.bbs$commitsPage);

        this.bbs$refreshTopButton();
    }

    @Override
    public boolean bbs$isCommitsTabActive()
    {
        if (this.bbs$commitsPage == null)
        {
            return false;
        }

        /* Follow the tab that is current *right now*, not the stale visibility flag: during
         * closeTab() the tab list has already removed a tab but openTab() has not run yet.
         * After closing the commits tab this is the film id (don't write the pseudo id into the
         * film tab); after closing the last film tab it is the commits id (keep it as commits). */
        UIEditorDashboardPanel editor = (UIEditorDashboardPanel) (Object) this;

        return CommitUi.isCommitsTab(editor.tabs.getCurrentId());
    }

    @Override
    public void bbs$showCommitsTab()
    {
        if (this.bbs$commitsPage == null)
        {
            return;
        }

        UIEditorDashboardPanel editor = (UIEditorDashboardPanel) (Object) this;

        this.bbs$commitsTab = true;

        editor.editor.setVisible(false);

        if (editor.landing != null)
        {
            editor.landing.setVisible(false);
        }

        this.bbs$commitsPage.setVisible(true);
        this.bbs$commitsPage.focusFilm("");
        this.bbs$commitsPage.requestAll();
    }

    @Override
    public void bbs$hideCommitsTab()
    {
        this.bbs$refreshTopButton();

        if (!this.bbs$commitsTab)
        {
            return;
        }

        UIEditorDashboardPanel editor = (UIEditorDashboardPanel) (Object) this;

        this.bbs$commitsTab = false;

        if (this.bbs$commitsPage != null)
        {
            this.bbs$commitsPage.setVisible(false);
        }

        editor.editor.setVisible(true);
        editor.syncLanding();
    }

    /**
     * The icon in the top bar is the way in, not a second switch: while a commits tab exists the
     * tab itself is the switch, so the icon goes away and comes back once that tab is closed.
     */
    @Unique
    private void bbs$refreshTopButton()
    {
        UITabList tabs = ((UIEditorDashboardPanel) (Object) this).tabs;
        boolean open = false;

        if (tabs != null)
        {
            for (DataTab tab : tabs)
            {
                if (CommitUi.isCommitsTab(tab.dataId))
                {
                    open = true;

                    break;
                }
            }
        }

        UIFilmPanel panel = (UIFilmPanel) (Object) this;
        boolean changed = false;

        for (CommitUi.UICommitsTopButton button : panel.topBar.getChildren(CommitUi.UICommitsTopButton.class))
        {
            if (button.isVisible() == open)
            {
                button.setVisible(!open);
                changed = true;
            }
        }

        if (changed)
        {
            panel.topBar.resize();
        }
    }

    @Override
    public void bbs$openCommitsTab()
    {
        UITabList tabs = ((UIEditorDashboardPanel) (Object) this).tabs;
        int index = 0;
        int found = -1;

        for (DataTab tab : tabs)
        {
            if (CommitUi.isCommitsTab(tab.dataId))
            {
                found = index;
                break;
            }

            index++;
        }

        if (found >= 0)
        {
            tabs.switchTab(found);
        }
        else
        {
            tabs.addTab();
            tabs.setOpenId(CommitUi.TAB_ID);
        }

        /* Keep the commit page where its top-bar icon was: the leftmost tab. */
        ((ITabListLayout) tabs).bbs$moveTabToFront(CommitUi.TAB_ID);

        this.bbs$showCommitsTab();
        this.bbs$refreshTopButton();
    }

    @Inject(method = "getTabIcon", at = @At("HEAD"), cancellable = true)
    private void bbs$getTabIcon(String id, CallbackInfoReturnable<Icon> cir)
    {
        if (CommitUi.isCommitsTab(id))
        {
            cir.setReturnValue(Icons.GRAPH);
        }
    }
}
