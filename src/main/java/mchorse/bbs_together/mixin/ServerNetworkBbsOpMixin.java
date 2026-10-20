package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_together.commits.FilmBranchStore;
import mchorse.bbs_together.commits.FilmCommitStore;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import mchorse.bbs_together.behaviors.ModelBlockTracker;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.FilmLoadContext;
import mchorse.bbs_together.network.FilmWriteGuard;
import mchorse.bbs_together.network.ServerCrusherPool;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * All ServerNetwork injections in one class: the level-0 hard stop, the model-block
 * tracker arming, the film-load player context and the per-asset lock gates.
 *
 * <p>These handlers already gate on {@code PermissionUtils.arePanelsAllowed} (global gamerule /
 * OP), but a level-0 player must be refused even when the gamerule opens editing to everyone.
 * We cancel at HEAD: model-block form/transform writes. {@code handlePlayerFormPacket} is
 * deliberately NOT blocked — it only sets the player's own morph (a browsing feature, the
 * disguise list's "become this form" action), not any editable asset.</p>
 *
 * <p>{@code handleManagerDataPacket} is reimplemented below so read operations (LOAD / KEYS /
 * BACKUPS) keep working for everybody the global gate allows, while every write branch
 * (SAVE / RENAME / DELETE / ADD_FOLDER / RENAME_FOLDER / DELETE_FOLDER) is checked against both
 * the player's film permission and the film's asset lock. This closes the hole left by the
 * {@code bbsEditing} gamerule defaulting to {@code true} and keeps a locked film locked even
 * for write paths BBS-Together does not originate itself.</p>
 */
@Mixin(value = ServerNetwork.class, remap = false)
public class ServerNetworkBbsOpMixin
{
    /** BBS 2.6's manager-data LOAD calls {@code toData()} without a null check. */
    private static final boolean BBS_26 = FabricLoader.getInstance().getModContainer("bbs")
        .map((container) -> container.getMetadata().getVersion().getFriendlyString().startsWith("2.6"))
        .orElse(false);

    @Inject(method = "handleManagerDataPacket", at = @At("HEAD"), remap = false)
    private static void bbs$rememberFilmLoadPlayer(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        FilmLoadContext.set(player);
    }

    @Inject(method = "handleManagerDataPacket", at = @At("RETURN"), remap = false)
    private static void bbs$forgetFilmLoadPlayer(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        FilmLoadContext.clear();
    }

    /** The block position rides in the metadata of the last model-block-form chunk. */
    private static BlockPos readModelBlockPos(PacketByteBuf buf)
    {
        try
        {
            /* Chunk header: [id][index][total][size][bytes] (+ metadata on the last chunk) */
            PacketByteBuf copy = new PacketByteBuf(buf.duplicate());

            copy.readInt(); // chunk id

            int index = copy.readInt();
            int total = copy.readInt();
            int size = copy.readInt();

            copy.skipBytes(size);

            if (index == total - 1 && copy.isReadable(1))
            {
                return copy.readBlockPos();
            }
        }
        catch (Exception ignored)
        {}

        return null;
    }

    @Inject(method = "handleModelBlockFormPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockModelBlockForm(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.MODEL_BLOCKS))
        {
            ci.cancel();

            return;
        }

        BlockPos pos = readModelBlockPos(buf);

        if (pos != null && !AssetLockManager.canEdit(player.getUuid(), AssetLockType.MODEL_BLOCK, AssetLockManager.modelBlockId(player, pos)))
        {
            AssetLockManager.notifyLocked(server, player, AssetLockType.MODEL_BLOCK);
            ci.cancel();

            return;
        }

        /* Arm the behavior tracker only for writes that are actually going to land. */
        if (pos != null)
        {
            ModelBlockTracker.arm(pos, player.getUuid());
        }
    }

    @Inject(method = "handleModelBlockTransformsPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockModelBlockTransforms(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.MODEL_BLOCKS))
        {
            ci.cancel();
        }
    }

    /**
     * Reimplementation of BBS' film manager-data handler: reads are answered exactly like the
     * original, writes are refused when the player may not edit films or the specific film is
     * locked. The original handler is cancelled for every caller so a locked film cannot be
     * written through a stale path.
     */
    @Inject(method = "handleManagerDataPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$gateManagerData(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        ci.cancel();

        /* Keep BBS's own global gate for the operations we forward. */
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        ServerCrusherPool.get(player.getUuid()).receive(buf, (bytes, packetByteBuf) ->
        {
            FilmLoadContext.set(player);

            try
            {
                MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);
                int callbackId = packetByteBuf.readInt();
                int opOrdinal = packetByteBuf.readInt();

                if (opOrdinal < 0 || opOrdinal >= RepositoryOperation.values().length)
                {
                    return;
                }

                RepositoryOperation op = RepositoryOperation.values()[opOrdinal];
                FilmManager films = BBSMod.getFilms();
                boolean mayEditFilms = BbsOpManager.isAllowed(player, BbsOpDomain.FILM);
                String id = data.getString("id");

                switch (op)
                {
                    case LOAD:
                    {
                        Film film = films.load(id);

                        ServerNetwork.sendManagerData(player, callbackId, op, film == null ? new ByteType(false) : film.toData());
                        break;
                    }

                    case KEYS:
                    {
                        ServerNetwork.sendManagerData(player, callbackId, op, DataStorageUtils.stringListToData(films.getKeys()));
                        break;
                    }
                    case BACKUPS:
                    {
                        ServerNetwork.sendManagerData(player, callbackId, op, DataStorageUtils.stringListToData(films.getBackupKeys(id)));
                        break;
                    }

                    case SAVE:
                    {
                        if (!mayEditFilms)
                        {
                            return;
                        }

                        if (!FilmWriteGuard.canWrite(player, id))
                        {
                            if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, id))
                            {
                                AssetLockManager.notifyLocked(server, player, AssetLockType.FILM);
                            }

                            return;
                        }

                        films.save(id, data.getMap("data"));
                        break;
                    }
                    case RENAME:
                    {
                        String from = data.getString("from");
                        String to = data.getString("to");

                        if (!mayEditFilms
                            || !FilmWriteGuard.canWrite(player, from)
                            || !FilmWriteGuard.canWrite(player, to))
                        {
                            if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, from)
                                || !AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, to))
                            {
                                AssetLockManager.notifyLocked(server, player, AssetLockType.FILM);
                            }

                            return;
                        }

                        films.rename(from, to);
                        AssetLockManager.rename(AssetLockType.FILM, from, to);
                        break;
                    }
                    case DELETE:
                    {
                        if (!mayEditFilms || !FilmWriteGuard.canWrite(player, id))
                        {
                            if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, id))
                            {
                                AssetLockManager.notifyLocked(server, player, AssetLockType.FILM);
                            }

                            return;
                        }

                        films.delete(id);
                        AssetLockManager.removeForAsset(AssetLockType.FILM, id);
                        break;
                    }
                    case ADD_FOLDER:
                    {
                        if (!mayEditFilms)
                        {
                            return;
                        }

                        ServerNetwork.sendManagerData(player, callbackId, op, new ByteType(films.addFolder(data.getString("folder"))));
                        break;
                    }
                    case RENAME_FOLDER:
                    {
                        String from = data.getString("from");
                        String to = data.getString("to");

                        if (!mayEditFilms || !FilmWriteGuard.canWriteFolder(player, from))
                        {
                            return;
                        }

                        ArrayList<String> moved = new ArrayList<>();

                        for (String key : films.getKeys())
                        {
                            if (key.equals(from) || key.startsWith(from + "/"))
                            {
                                moved.add(key);
                            }
                        }

                        ServerNetwork.sendManagerData(player, callbackId, op, new ByteType(films.renameFolder(from, to)));
                        AssetLockManager.renamePrefix(AssetLockType.FILM, from, to);

                        for (String oldId : moved)
                        {
                            FilmCommitStore.renameFilm(oldId, to + oldId.substring(from.length()));
                            FilmBranchStore.renameFilm(oldId, to + oldId.substring(from.length()));
                        }

                        break;
                    }
                    case DELETE_FOLDER:
                    {
                        String folder = data.getString("folder");

                        if (!mayEditFilms || !FilmWriteGuard.canWriteFolder(player, folder))
                        {
                            return;
                        }

                        ArrayList<String> deleted = new ArrayList<>();

                        for (String key : films.getKeys())
                        {
                            if (key.equals(folder) || key.startsWith(folder + "/"))
                            {
                                deleted.add(key);
                            }
                        }

                        ServerNetwork.sendManagerData(player, callbackId, op, new ByteType(films.deleteFolder(folder)));

                        for (String deletedId : deleted)
                        {
                            TogetherServerNetwork.markFilmDeleted(deletedId);
                            AssetLockManager.removeForAsset(AssetLockType.FILM, deletedId);
                            FilmCommitStore.deleteFilm(deletedId);
                            FilmBranchStore.forgetFilm(deletedId);
                        }

                        break;
                    }
                    default:
                        break;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                FilmLoadContext.clear();
            }
        });
    }

    /**
     * BBS' native film delta path (used by actions / older clients). Reimplemented for the same
     * reason as the manager-data handler: the film id is inside the compressed payload, so the
     * lock cannot be checked without decoding it here.
     */
    @Inject(method = "handleSyncData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$gateSyncData(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        ci.cancel();

        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        ServerCrusherPool.get(player.getUuid()).receive(buf, (bytes, packetByteBuf) ->
        {
            try
            {
                String filmId = packetByteBuf.readString();
                List<String> path = new ArrayList<>();
                int count = buf.readInt();

                for (int i = 0; i < count; i++)
                {
                    path.add(buf.readString());
                }

                BaseType data = DataStorageUtils.readFromBytes(bytes);

                server.execute(() ->
                {
                    if (!BbsOpManager.isAllowed(player, BbsOpDomain.FILM))
                    {
                        return;
                    }

                    if (!AssetLockManager.canEdit(player.getUuid(), AssetLockType.FILM, filmId))
                    {
                        AssetLockManager.notifyLocked(server, player, AssetLockType.FILM);
                        return;
                    }

                    BBSMod.getActions().syncData(filmId, new DataPath(path), data);
                });
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
    }

    /**
     * BBS 2.6 calls {@code film.toData()} on a manager LOAD without checking for a missing
     * film, which disconnects the player. 2.7 already handles null (and sends the explicit
     * "does not exist" response), so substitute an empty film only on 2.6. The target is the
     * lambda that holds the manager-data switch; both 2.6 and 2.7 compile it to the same name.
     */
    @Redirect(
        method = "lambda$handleManagerDataPacket$24",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/film/FilmManager;load(Ljava/lang/String;)Lmchorse/bbs_mod/settings/values/core/ValueGroup;"),
        require = 0,
        remap = false
    )
    private static ValueGroup together$nullFilmFix(FilmManager films, String id)
    {
        ValueGroup film = films.load(id);

        if (film == null && BBS_26)
        {
            return new Film();
        }

        return film;
    }

}
