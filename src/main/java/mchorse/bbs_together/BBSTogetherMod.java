package mchorse.bbs_together;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_together.bbsop.BbsOpManager;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherServerNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class BBSTogetherMod
implements ModInitializer {
    public static final String MOD_ID = "bbs_together";

    public void onInitialize() {
        TogetherServerNetwork.setup();
        CommandRegistrationCallback.EVENT.register(TogetherCommands::register);
        ServerPlayConnectionEvents.JOIN.register((handler, packetSender, server) -> {
            server.execute(() -> {
                /* Before the permission syncs below, so the first join already learns the level */
                BbsOpManager.onPlayerJoin(server, handler.player);

                /* B4: the asset manifest is client-pulled now — the client sends its cached
                 * manifest fingerprint right after joining, and identical manifests cost a
                 * round trip instead of a full re-send. */
                TogetherServerNetwork.sendInitialFilmSync(server, handler.player);
                BehaviorLogManager.onPlayerJoin(handler.player);
                TogetherServerNetwork.sendPermissions(server, handler.player);
                BbsOpManager.syncToPlayer(handler.player);
                AssetLockManager.syncToPlayer(handler.player);
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TogetherServerNetwork.clearPlayerState(handler.player, server));

        /* A locked model block is not breakable, or the lock would only protect the
         * properties of a block anyone could remove. Level-2 admins pass canEdit. */
        PlayerBlockBreakEvents.BEFORE.register(BBSTogetherMod::onBlockBreak);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BehaviorLogManager.onServerStarted(server);
            BbsOpManager.onServerStarted(server);
            AssetLockManager.onServerStarted(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BehaviorLogManager.onServerStopping();
            BbsOpManager.onServerStopping();
            AssetLockManager.onServerStopping();
            TogetherServerNetwork.flushPendingFilmSaves(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BehaviorLogManager.onServerStopped();
            BbsOpManager.onServerStopped();
            AssetLockManager.onServerStopped();
            TogetherServerNetwork.reset();
        });
    }

    private static boolean onBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity)
    {
        if (world.isClient())
        {
            return true;
        }

        BlockEntity entity = blockEntity != null ? blockEntity : world.getBlockEntity(pos);

        if (!(entity instanceof ModelBlockEntity))
        {
            return true;
        }

        String id = AssetLockManager.modelBlockId(world, pos);

        if (AssetLockManager.canEdit(player.getUuid(), AssetLockType.MODEL_BLOCK, id))
        {
            return true;
        }

        if (player instanceof ServerPlayerEntity serverPlayer && serverPlayer.getServer() != null)
        {
            AssetLockManager.notifyLocked(serverPlayer.getServer(), serverPlayer, AssetLockType.MODEL_BLOCK);
        }

        return false;
    }
}
