package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.actions.ActionPlayer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_together.network.CoopSessionManager;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a coop take's set free of the cast it is replacing.
 *
 * <p>{@code ActionPlayer.updateReplayEntities} puts a body out for every actor replay and skips
 * only the one the recording player owns, which is meant as blocking reference for a solo take.
 * In a coop take every other member is standing on the set themselves, so their body - still
 * drawn at the blocking of the PREVIOUS take, since the replay is only overwritten at the merge
 * - duplicates a person who is right there.</p>
 *
 * <p>The claimed replays are therefore dropped from the actor map (and their bodies discarded)
 * right before the map is broadcast, so clients get the cleaned cast in one packet and
 * {@code reviveLostActors}, which only rebuilds what is still in the map, cannot put it back.
 * Replays nobody claimed keep their bodies: that is genuine reference.</p>
 */
@Mixin(value = ActionPlayer.class, remap = false)
public abstract class ActionPlayerCoopCastMixin {
    @Shadow
    public Film film;

    @Shadow
    private Map<String, LivingEntity> actors;

    @Inject(
        method = "updateReplayEntities",
        at = @At(value = "INVOKE", target = "Lmchorse/bbs_mod/actions/ActionPlayer;broadcastActors()V"),
        remap = false
    )
    private void together$hideCoopRecordedBodies(CallbackInfo ci) {
        Set<Integer> picks = CoopSessionManager.recordingPicks(this.film.getId());

        if (picks.isEmpty()) {
            return;
        }

        List<Replay> replays = this.film.replays.getList();

        for (int index : picks) {
            if (index < 0 || index >= replays.size()) {
                continue;
            }

            LivingEntity body = this.actors.remove(replays.get(index).getId());

            /* Only an actor body is ours to take away: a first person replay can be mapped to
             * the recording player themselves, and that person is not a prop. */
            if (body instanceof ActorEntity actor) {
                actor.dropPickedUp();
                actor.discard();
            }
        }
    }
}
