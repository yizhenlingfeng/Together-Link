package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.utils.repos.FilmRepository;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Serve the most recently received copy of a film straight away when the editor opens it again.
 *
 * <p>Opening a film on a server normally waits for a manager-data round trip to the server and a
 * full gzip/decompress pass on the client. Closing and reopening the same film used to pay that
 * cost every time, which is perceived as a multi-second pause. The cache holds the complete film
 * for a handful of recently opened films; the editor still asks the server for the current
 * branch state immediately afterwards (the film panel sends its film selection on fill), so a
 * stale cache is corrected as soon as the authoritative copy arrives.</p>
 */
@Mixin(FilmRepository.class)
public class FilmRepositoryMixin
{
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private void bbs$useCachedFilm(String id, Consumer<Film> callback, CallbackInfo ci)
    {
        /* Single-player loads from the local file and is already fast; using the cache there
         * would risk showing the state from before the last edit. */
        if (callback == null || !TogetherClientNetwork.isConnected() || MinecraftClient.getInstance().isIntegratedServerRunning())
        {
            return;
        }

        MapType data = TogetherClientNetwork.cachedFilmData(id);

        if (data == null)
        {
            return;
        }

        try
        {
            Film film = new Film();

            film.setId(id);
            film.fromData(data);
            TogetherClientNetwork.beginCachedFilmLoad(id);

            try
            {
                callback.accept(film);
            }
            finally
            {
                TogetherClientNetwork.endCachedFilmLoad();
            }

            ci.cancel();
        }
        catch (Exception ignored)
        {
            /* A malformed or outdated cache must never stop the normal server load. */
        }
    }
}
