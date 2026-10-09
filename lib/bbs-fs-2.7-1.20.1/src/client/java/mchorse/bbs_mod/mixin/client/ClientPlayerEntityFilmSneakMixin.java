package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.film.FilmPlayerPose;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep keyboard input from replacing the film's crouch between world ticks. */
@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityFilmSneakMixin
{
    @Inject(method = "isSneaking", at = @At("HEAD"), cancellable = true)
    private void bbsFilmIsSneaking(CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = FilmPlayerPose.get(((ClientPlayerEntity) (Object) this).getId());

        if (pose != null)
        {
            info.setReturnValue(pose.sneaking());
        }
    }

    @Inject(method = "isInSneakingPose", at = @At("HEAD"), cancellable = true)
    private void bbsFilmIsInSneakingPose(CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = FilmPlayerPose.get(((ClientPlayerEntity) (Object) this).getId());

        if (pose != null)
        {
            info.setReturnValue(pose.pose() == EntityPose.CROUCHING);
        }
    }
}
