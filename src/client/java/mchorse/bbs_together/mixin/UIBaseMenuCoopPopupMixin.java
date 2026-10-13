package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_together.network.CoopClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the pinned cooperative-recording invite popup on top of ANY BBS screen (the
 * dashboard, the model editor, ...). The HUD callback covers the in-world case; screens
 * draw over the HUD, so the popup needs this second, BBS-side render path to stay
 * visible there. Hotkeys are polled once per frame in the HUD callback for both paths.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIBaseMenu.class, remap = false)
public class UIBaseMenuCoopPopupMixin
{
    @Inject(method = "renderMenu", at = @At("TAIL"), remap = false)
    private void together$renderCoopPopup(UIRenderingContext context, int mouseX, int mouseY, CallbackInfo ci)
    {
        if (!mchorse.bbs_together.network.TogetherClientNetwork.isConnected() || CoopClient.pendingInvite == null)
        {
            return;
        }

        UIBaseMenu menu = (UIBaseMenu) (Object) this;

        CoopClient.renderPopup(context.batcher, menu.width, menu.height);
    }
}
