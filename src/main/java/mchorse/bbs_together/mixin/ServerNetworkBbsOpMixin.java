package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.network.ServerPacketCrusher;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import mchorse.bbs_together.behaviors.ModelBlockTracker;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.bbsop.BbsOpManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All ServerNetwork injections in one class: the level-0 hard stop, the model-block
 * tracker arming and the null-film load fix.
 *
 * <p>These handlers already gate on {@code PermissionUtils.arePanelsAllowed} (global gamerule /
 * OP), but a level-0 player must be refused even when the gamerule opens editing to everyone.
 * We cancel at HEAD: model-block form/transform writes. {@code handlePlayerFormPacket} is
 * deliberately NOT blocked — it only sets the player's own morph (a browsing feature, the
 * disguise list's "become this form" action), not any editable asset.</p>
 *
 * <p>{@code handleManagerDataPacket} is special-cased below: cancelling it outright would also
 * kill LOAD / KEYS / BACKUPS — the read operations a read-only player needs just to browse and
 * download films. So we cancel, then re-run only the read branches ourselves; every write
 * branch (SAVE / RENAME / DELETE / ADD_FOLDER / RENAME_FOLDER / DELETE_FOLDER) is dropped.
 * This closes the hole left by the {@code bbsEditing} gamerule defaulting to {@code true}.</p>
 */
@Mixin(value = ServerNetwork.class, remap = false)
public class ServerNetworkBbsOpMixin
{
    @Shadow(remap = false)
    private static ServerPacketCrusher crusher;

    @Inject(method = "handleModelBlockFormPacket", at = @At("HEAD"))
    private static void together$armModelBlockTracker(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
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

            /* Payload metadata (the position) rides only on the last chunk */
            if (index == total - 1 && copy.isReadable(1))
            {
                BlockPos pos = copy.readBlockPos();

                ModelBlockTracker.arm(pos, player.getUuid());
            }
        }
        catch (Exception ignored)
        {}
    }

    @Inject(method = "handleModelBlockFormPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$blockModelBlockForm(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (!BbsOpManager.isAllowed(player, BbsOpDomain.MODEL_BLOCKS))
        {
            ci.cancel();
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
     * Players who may not edit films (level 0, or a level-1 editor whose film domain was
     * denied) keep read access (browse + download films) but every write operation
     * is silently refused. Read operations are answered exactly like the original handler.
     */
    @Inject(method = "handleManagerDataPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void together$gateManagerData(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf, CallbackInfo ci)
    {
        if (BbsOpManager.isAllowed(player, BbsOpDomain.FILM))
        {
            return;
        }

        ci.cancel();

        /* Keep BBS's own global gate for the read operations we forward. */
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);
            int callbackId = packetByteBuf.readInt();
            RepositoryOperation op = RepositoryOperation.values()[packetByteBuf.readInt()];
            FilmManager films = BBSMod.getFilms();

            switch (op)
            {
                case LOAD:
                {
                    Film film = films.load(data.getString("id"));

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
                    ServerNetwork.sendManagerData(player, callbackId, op, DataStorageUtils.stringListToData(films.getBackupKeys(data.getString("id"))));
                    break;
                }
                default:
                    break;
            }
        });
    }

    /**
     * Fix BBS 2.6 bug: ServerNetwork.handleManagerDataPacket calls film.toData()
     * without null check when loading a non-existent film, causing player disconnect.
     * require=0: soft-fail if another mod redirects the same call.
     */
    @Redirect(method={"handleManagerDataPacket"}, at=@At(value="INVOKE", target="Lmchorse/bbs_mod/film/FilmManager;load(Ljava/lang/String;)Lmchorse/bbs_mod/film/Film;", remap=false), remap=false, require=0)
    private Film together_safeLoadFilm(FilmManager films, String id)
    {
        Film film = films.load(id);

        return film == null ? new Film() : film;
    }
}
