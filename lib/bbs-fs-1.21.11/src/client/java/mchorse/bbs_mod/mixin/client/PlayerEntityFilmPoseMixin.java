package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.film.FilmPlayerPose;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public class PlayerEntityFilmPoseMixin
{
    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true)
    private void bbsFilmUpdatePose(CallbackInfo info)
    {
        if ((Object) this instanceof ClientPlayerEntity player)
        {
            FilmPlayerPose pose = FilmPlayerPose.get(player.getId());

            if (pose != null)
            {
                /* Vanilla rejects crouching while creative flight is enabled. Use the same pose
                 * the film writes at END_WORLD_TICK throughout the tick, without changing flight
                 * permissions or overriding the recorded swimming/gliding pose with a crouch. */
                player.setPose(pose.pose());
                info.cancel();
            }
        }
    }
}
