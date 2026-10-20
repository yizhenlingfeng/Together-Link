package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_together.sync.SyncStatusRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw the sync indicator after tooltips and notifications: it must stay on top of every
 * dashboard bar/menu/overlay. It is a pure draw call, never a UI element, so it cannot
 * intercept clicks.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIContext.class, remap = false)
public class SyncHudOverlayMixin {
    @Inject(method = "postRender", at = @At("TAIL"), remap = false)
    private void bbs$renderSyncHud(CallbackInfo ci) {
        UIContext self = (UIContext) (Object) this;

        if (self.menu instanceof UIDashboard dashboard) {
            SyncStatusRenderer.render(self, dashboard);
        }
    }
}
