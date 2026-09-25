package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.network.ServerNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fix BBS 2.6 bug: ServerNetwork.handleManagerDataPacket calls film.toData()
 * without null check when loading a non-existent film, causing player disconnect.
 */
@Mixin(value={ServerNetwork.class}, remap=false)
public class ServerNetworkNullFilmFixMixin {
    @Redirect(method={"handleManagerDataPacket"}, at=@At(value="INVOKE", target="Lmchorse/bbs_mod/film/FilmManager;load(Ljava/lang/String;)Lmchorse/bbs_mod/film/Film;", remap=false), remap=false, require=0)
    private Film together_safeLoadFilm(FilmManager films, String id) {
        Film film = films.load(id);
        if (film == null) {
            film = new Film();
        }
        return film;
    }
}
