package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.network.IBufferReceiver;
import mchorse.bbs_mod.network.PacketCrusher;
import net.minecraft.network.PacketByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Last-resort bounds check for BBS' own static crusher (model block form/transform
 * channels). The Together channels use {@code SafeServerPacketCrusher}, but BBS handlers
 * still feed the static crusher; a single packet with a huge {@code total} used to request
 * a multi-gigabyte {@code ByteArrayOutputStream}.
 */
@Mixin(value = PacketCrusher.class, remap = false)
public class PacketCrusherBoundsMixin
{
    private static final int MAX_CHUNKS = 4096;

    @Inject(method = "receive", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$validateChunkHeader(PacketByteBuf buf, IBufferReceiver receiver, CallbackInfo ci)
    {
        if (buf.readableBytes() < 16)
        {
            ci.cancel();
            return;
        }

        PacketByteBuf copy = new PacketByteBuf(buf.duplicate());
        int id = copy.readInt();
        int index = copy.readInt();
        int total = copy.readInt();
        int size = copy.readInt();

        if (id < 0 || total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
            || size < 0 || size > PacketCrusher.BUFFER_SIZE || copy.readableBytes() < size)
        {
            ci.cancel();
        }
    }
}
