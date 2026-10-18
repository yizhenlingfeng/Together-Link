package mchorse.bbs_together.commits;

/**
 * One monitor for every read and write of a BBS film {@code .dat}.
 *
 * <p>BBS saves films from the Netty thread (an incoming manager-data SAVE) while this mod
 * saves the same file from the server thread (branch workspace mirror, debounced edit save,
 * commit, shutdown). Those two writers used to overlap with readers: the gzip stream then
 * ends in the middle of a value ("Unexpected end of ZLIB input stream"), and the loser of a
 * simultaneous write gets Windows' "file is being used by another process". Serializing the
 * compressed storage covers both directions, since {@code CompressedDataStorage} is only
 * used by {@code FilmManager}.</p>
 */
public final class FilmFileLock
{
    public static final Object LOCK = new Object();

    private FilmFileLock()
    {}
}
