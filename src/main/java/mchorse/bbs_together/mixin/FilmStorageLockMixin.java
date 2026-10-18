package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.storage.DataGzipStorage;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.manager.storage.CompressedDataStorage;
import mchorse.bbs_together.commits.FilmFileLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

/**
 * Serializes the actual gzip read/write of film files. See {@link FilmFileLock} for why:
 * BBS writes films from the Netty thread while this mod writes them from the server thread,
 * which produced truncated reads and "another process is using this file" saves.
 *
 * <p>{@code CompressedDataStorage} is only instantiated by {@code FilmManager}, so this
 * doesn't touch model/form storage.</p>
 */
@Mixin(value = CompressedDataStorage.class, remap = false)
public class FilmStorageLockMixin
{
    private static final int WRITE_ATTEMPTS = 3;

    @Redirect(method = "load", at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/data/storage/DataGzipStorage;read()Lmchorse/bbs_mod/data/types/BaseType;", remap = false), remap = false)
    private static BaseType together$lockedRead(DataGzipStorage storage) throws IOException
    {
        synchronized (FilmFileLock.LOCK)
        {
            return storage.read();
        }
    }

    @Redirect(method = "save", at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/data/storage/DataGzipStorage;write(Lmchorse/bbs_mod/data/types/BaseType;)V", remap = false), remap = false)
    private static void together$lockedWrite(DataGzipStorage storage, BaseType data) throws IOException
    {
        synchronized (FilmFileLock.LOCK)
        {
            /* An external scanner (or a handle that is still closing) can briefly refuse the
             * write on Windows. The old file was opened with truncation, so a failed attempt
             * may leave it partial — retrying rewrites it completely. */
            IOException last = null;

            for (int attempt = 0; attempt < WRITE_ATTEMPTS; attempt++)
            {
                try
                {
                    storage.write(data);

                    return;
                }
                catch (IOException e)
                {
                    last = e;
                }

                try
                {
                    Thread.sleep(100L * (attempt + 1));
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (last != null)
            {
                throw last;
            }
        }
    }
}
