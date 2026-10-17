package mchorse.bbs_together.ui.commits;

/**
 * Implemented by the film editor panel (through a mixin) so that the commit graph can be opened as
 * one of its tabs, next to the films, instead of replacing the whole dashboard panel.
 */
public interface ICommitsTabHost
{
    boolean bbs$isCommitsTabActive();

    void bbs$showCommitsTab();

    void bbs$hideCommitsTab();

    void bbs$openCommitsTab();
}
