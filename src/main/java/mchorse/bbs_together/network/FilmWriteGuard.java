package mchorse.bbs_together.network;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * One gate for every server-side film write. Film writes used to be split across the BBS
 * manager-data path, the dedicated Together save/delta path, commits/branches, undo and the
 * legacy Fix28 snapshot channels; each one remembered a different subset of the rules. A
 * missing combination (most notably the cooperative-recording lock) let one path overwrite
 * a film that another path had just frozen.
 *
 * <p>Keep this predicate free of side effects so it can be used from every handler. Callers
 * that want user feedback should send their own localized message.</p>
 */
public final class FilmWriteGuard {
    private FilmWriteGuard() {}

    public static boolean canWrite(ServerPlayerEntity player, String filmId, String branch) {
        if (player == null || filmId == null || filmId.isEmpty()) {
            return false;
        }
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return false;
        }
        if (TogetherServerNetwork.isFilmDeleted(filmId)) {
            return false;
        }
        if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, filmId)) {
            return false;
        }

        return !CoopSessionManager.isFilmLockedForEditing(filmId, branch);
    }

    public static boolean canWrite(ServerPlayerEntity player, String filmId) {
        return filmId != null && canWrite(player, filmId, FilmBranchStore.branchOf(filmId, player.getUuid()));
    }

    /** Folder rename/delete must not touch a film that is asset-locked or in a coop session. */
    public static boolean canWriteFolder(ServerPlayerEntity player, String folder) {
        if (player == null || folder == null || folder.isEmpty()) {
            return false;
        }
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM)) {
            return false;
        }

        String prefix = folder.endsWith("/") ? folder : folder + "/";

        for (String id : BBSMod.getFilms().getKeys()) {
            if (!id.equals(folder) && !id.startsWith(prefix)) {
                continue;
            }

            if (TogetherServerNetwork.isFilmDeleted(id)
                || !AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, id)
                || CoopSessionManager.isFilmLockedForEditing(id, FilmBranchStore.branchOf(id, player.getUuid()))) {
                return false;
            }
        }

        return true;
    }
}
