package mchorse.bbs_together.ui;

/**
 * Marks a {@code UIDataPathList} as the film list rather than the model editor's asset
 * list. Both use the same list class and a film id may collide with a model id; the lock
 * badge must only appear on the film list.
 */
public interface IFilmListMarker
{
    boolean bbs$isFilmList();

    void bbs$setFilmList(boolean filmList);
}
