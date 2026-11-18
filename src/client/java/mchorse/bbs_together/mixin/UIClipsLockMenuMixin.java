package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.IFactory;
import mchorse.bbs_together.ui.ClipLockUi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds one more context menu consumer to the camera timeline. BBS runs every
 * consumer of {@code UIElement.context(...)} in registration order and the icon
 * bar sorts by verb zone with a stable sort, so a COMMON-zone icon added here
 * lands after the presets (≡) verb of the built-in menu.
 */
@Mixin(value = UIClips.class, remap = false)
public class UIClipsLockMenuMixin
{
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void bbs_together$addClipLockMenu(IUIClipsDelegate delegate, IFactory<Clip, ClipFactoryData> factory, CallbackInfo ci)
    {
        ((UIElement) (Object) this).context((menu) -> ClipLockUi.install(menu, (UIClips) (Object) this, delegate));
    }
}
