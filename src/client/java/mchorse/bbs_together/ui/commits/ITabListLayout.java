package mchorse.bbs_together.ui.commits;

/**
 * Extra tab ordering used by the commit page. It is implemented on BBS' {@code UITabList} by a
 * mixin, so no BBS source has to know about the pinned tab.
 */
public interface ITabListLayout
{
    /** Move the tab holding {@code id} to the first position, keeping the current tab current. */
    void bbs$moveTabToFront(String id);
}
