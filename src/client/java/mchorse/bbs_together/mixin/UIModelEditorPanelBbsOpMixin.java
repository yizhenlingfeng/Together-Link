package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Model editor gate for locked models. Uploads for a locked model are already refused on
 * the server, but letting a non-whitelisted player open and edit it only to lose the work at
 * sync time is worse than refusing the tab up front. Renaming falls under the same rule:
 * a rename would otherwise write a new model id the old lock does not cover.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIModelEditorPanel.class, remap = false)
public class UIModelEditorPanelBbsOpMixin
{
    @Inject(method = "requestData", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$blockLockedModel(String id, CallbackInfo ci)
    {
        if (id != null && !AssetLockClient.canEdit(AssetLockType.MODEL, id))
        {
            UIModelEditorPanel self = (UIModelEditorPanel) (Object) this;
            UIContext context = self.getContext();

            if (context != null)
            {
                context.notifyError(L10n.lang("bbs_together.locks.notify.model_locked"));
            }

            ci.cancel();
        }
    }

    @Inject(method = "forceSave", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$blockLockedModelSave(CallbackInfo ci)
    {
        UIModelEditorPanel self = (UIModelEditorPanel) (Object) this;
        ModelConfig data = self.getData();

        if (data != null && data.getId() != null && !AssetLockClient.canEdit(AssetLockType.MODEL, data.getId()))
        {
            UIContext context = self.getContext();

            if (context != null)
            {
                context.notifyError(L10n.lang("bbs_together.locks.notify.model_locked"));
            }

            ci.cancel();
        }
    }
}
