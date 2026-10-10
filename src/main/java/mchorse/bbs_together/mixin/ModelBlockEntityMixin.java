package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.behaviors.ModelBlockTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.UUID;

/**
 * Logs "player X picked model Y for the model block at P" right after the form
 * lands. The player comes from {@link ModelBlockTracker}, armed by the network
 * mixin on the packet's way in.
 */
@Mixin(ModelBlockEntity.class)
public class ModelBlockEntityMixin
{
    @Inject(method = "updateForm", at = @At("TAIL"))
    private void together$recordModelBlockPick(MapType data, World world, CallbackInfo ci)
    {
        try
        {
            if (world == null || world.isClient() || world.getServer() == null)
            {
                return;
            }

            Form form = ((ModelBlockEntity) (Object) this).getProperties().getForm();

            if (!(form instanceof ModelForm))
            {
                return;
            }

            String modelId = ((ModelForm) form).model.get();

            if (modelId == null || modelId.isEmpty())
            {
                return;
            }

            UUID playerId = ModelBlockTracker.take(((ModelBlockEntity) (Object) this).getPos());

            if (playerId == null)
            {
                return;
            }

            MinecraftServer server = world.getServer();
            BlockPos pos = ((ModelBlockEntity) (Object) this).getPos();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);

            if (player == null)
            {
                return;
            }

            BehaviorLogManager.record(player, BehaviorType.EDIT.id, BehaviorType.SUB_EDIT_MODEL_BLOCK, modelId, Collections.singletonList(modelId), Collections.singletonMap("pos", pos.getX() + " " + pos.getY() + " " + pos.getZ()));
        }
        catch (Exception ignored)
        {}
    }
}
