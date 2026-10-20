package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_together.network.ModelBlockPacketGuard;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drop a model block's vanilla update packet when its NBT exceeds the play-packet limit.
 * A 13 MiB BlockEntityUpdateS2CPacket otherwise trips the encoder and disconnects the
 * client. The block state remains correct; existing clients keep rendering the model.
 */
@Mixin(value = ModelBlockEntity.class, remap = false)
public class ModelBlockEntityPacketMixin {
    @Inject(method = "toUpdatePacket", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$dropOversizedUpdate(CallbackInfoReturnable<Packet<ClientPlayPacketListener>> cir) {
        ModelBlockEntity entity = (ModelBlockEntity) (Object) this;

        if (ModelBlockPacketGuard.nbtSize(entity) > ModelBlockPacketGuard.MAX_NBT_BYTES) {
            cir.setReturnValue(null);
        }
    }
}
