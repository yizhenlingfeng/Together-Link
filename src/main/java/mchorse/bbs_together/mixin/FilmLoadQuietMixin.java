package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.manager.BaseManager;
import mchorse.bbs_mod.utils.manager.FolderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * Loading a film that is not on disk yet is a normal state in the editor: a client may open a
 * film it created locally, or start playback while the upload is still in flight. BBS logged
 * that as a full ERROR with a FileNotFoundException stack trace, which made the server log look
 * broken. Film files that exist but cannot be read still take the normal error path.
 */
@Mixin(value = BaseManager.class, remap = false)
public class FilmLoadQuietMixin
{
    @Inject(method = "load(Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/core/ValueGroup;", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$quietMissingFilm(String id, CallbackInfoReturnable<ValueGroup> cir)
    {
        if (!(((Object) this) instanceof FilmManager))
        {
            return;
        }

        File file = ((FolderManager<?>) (Object) this).getFile(id);

        if (file == null || !file.isFile())
        {
            cir.setReturnValue(null);
        }
    }
}
