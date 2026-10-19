package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.manager.BaseManager;
import mchorse.bbs_mod.utils.manager.FolderManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.network.FilmLoadContext;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * Film storage bridge for BBS' manager-data LOAD and SAVE paths.
 *
 * <ul>
 *     <li>LOAD is answered with the requesting player's branch, avoiding a second full copy of
 *     the film when the editor sends its film selection right after opening.</li>
 *     <li>SAVE is routed into that player's working copy, so a side branch cannot overwrite the
 *     default-branch {@code .dat}.</li>
 *     <li>Missing film files are treated as a normal first-open and return {@code null} without
 *     BBS' FileNotFoundException stack trace.</li>
 * </ul>
 */
@Mixin(value = BaseManager.class, remap = false)
public class FilmLoadQuietMixin
{
    @Inject(method = "load(Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/core/ValueGroup;", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$branchAwareLoad(String id, CallbackInfoReturnable<ValueGroup> cir)
    {
        if (!(((Object) this) instanceof FilmManager))
        {
            return;
        }

        ServerPlayerEntity player = FilmLoadContext.get();

        if (player != null && id != null && !id.isEmpty() && BBSMod.getFilms().exists(id) && FilmLoadContext.beginBranchLoad())
        {
            try
            {
                String branch = FilmBranchStore.branchOf(id, player.getUuid());
                Film film = FilmBranchStore.working(id, branch);

                if (film != null)
                {
                    TogetherServerNetwork.markBranchStateDelivered(player, id, branch);
                    cir.setReturnValue(film);

                    return;
                }
            }
            finally
            {
                FilmLoadContext.endBranchLoad();
            }
        }

        File file = ((FolderManager<?>) (Object) this).getFile(id);

        if (file == null || !file.isFile())
        {
            cir.setReturnValue(null);
        }
    }

    /**
     * BBS' manager-data SAVE used to write the main {@code .dat} regardless of the sender's
     * branch. In a branch-aware session that meant a player editing a side branch overwrote
     * the default-branch file (and its backups) with their branch. Route the save into the
     * player's working copy instead; the dedicated film-save packet still merges and
     * broadcasts the change.
     */
    @Inject(method = "save(Ljava/lang/String;Lmchorse/bbs_mod/data/types/MapType;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$branchAwareSave(String id, MapType data, CallbackInfoReturnable<Boolean> cir)
    {
        if (!(((Object) this) instanceof FilmManager))
        {
            return;
        }

        ServerPlayerEntity player = FilmLoadContext.get();

        if (player == null || id == null || id.isEmpty() || data == null || !FilmLoadContext.beginBranchSave())
        {
            return;
        }

        try
        {
            String branch = FilmBranchStore.branchOf(id, player.getUuid());

            FilmBranchStore.setWorking(id, branch, data);
            FilmBranchStore.save(id, branch);
            cir.setReturnValue(true);
        }
        finally
        {
            FilmLoadContext.endBranchSave();
        }
    }
}
