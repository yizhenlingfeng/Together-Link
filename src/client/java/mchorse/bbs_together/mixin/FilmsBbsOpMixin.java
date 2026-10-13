package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The second recording entrance: BBS's record hotkey ({@code keyRecordReplay}) and the
 * server-triggered recording toggle both call {@link Films#startRecording(Film, int, int,
 * boolean)} directly, bypassing the film editor's {@code pickRecording} /
 * {@code FilmRecordingController.startRecording} pair that
 * {@code FilmRecordingControllerBbsOpMixin} gates. This closes the gap at the choke
 * point every client-side recording start funnels through: recording needs both the
 * film domain and the record sub-permission.
 *
 * <p>Only the 4-arg overload is targeted — the 3-arg one delegates to it, and its
 * descriptor (BBS types + primitives only) is stable across mappings with
 * {@code remap = false}.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = Films.class, remap = false)
public class FilmsBbsOpMixin
{
    @Inject(method = "startRecording(Lmchorse/bbs_mod/film/Film;IIZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockStartRecording(Film film, int replayId, int tick, boolean onMark, CallbackInfo ci)
    {
        if (!BbsOpClientState.canEdit(BbsOpDomain.FILM.id) || !BbsOpClientState.canEdit(BbsOpDomain.RECORD.id))
        {
            ci.cancel();
        }
    }
}
