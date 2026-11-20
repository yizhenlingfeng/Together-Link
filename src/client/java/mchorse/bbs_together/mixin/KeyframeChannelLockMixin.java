package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyframeChannel.class, remap = false)
public abstract class KeyframeChannelLockMixin
{
    @Inject(method = "fromData", at = @At("HEAD"), remap = false)
    private void bbs_together$beginData(BaseType data, CallbackInfo ci)
    {
        KeyframeLockClient.beginChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "fromData", at = @At("RETURN"), remap = false)
    private void bbs_together$endData(BaseType data, CallbackInfo ci)
    {
        KeyframeLockClient.endChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "copyKeyframes", at = @At("HEAD"), remap = false)
    private void bbs_together$beginCopy(KeyframeChannel<?> channel, CallbackInfo ci)
    {
        KeyframeLockClient.beginChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "copyKeyframes", at = @At("RETURN"), remap = false)
    private void bbs_together$endCopy(KeyframeChannel<?> channel, CallbackInfo ci)
    {
        KeyframeLockClient.endChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "dropRepeats", at = @At("HEAD"), remap = false)
    private void bbs_together$beginCompress(CallbackInfo ci)
    {
        KeyframeLockClient.beginChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "dropRepeats", at = @At("RETURN"), remap = false)
    private void bbs_together$endCompress(CallbackInfo ci)
    {
        KeyframeLockClient.endChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "putLoop", at = @At("HEAD"), remap = false)
    private void bbs_together$beginLoop(KeyframeLoop loop, CallbackInfo ci)
    {
        KeyframeLockClient.beginChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "putLoop", at = @At("RETURN"), remap = false)
    private void bbs_together$endLoop(KeyframeLoop loop, CallbackInfo ci)
    {
        KeyframeLockClient.endChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "removeLoop", at = @At("HEAD"), remap = false)
    private void bbs_together$beginLoopRemoval(String id, CallbackInfo ci)
    {
        KeyframeLockClient.beginChannelEdit((KeyframeChannel<?>) (Object) this);
    }

    @Inject(method = "removeLoop", at = @At("RETURN"), remap = false)
    private void bbs_together$endLoopRemoval(String id, CallbackInfo ci)
    {
        KeyframeLockClient.endChannelEdit((KeyframeChannel<?>) (Object) this);
    }
}
