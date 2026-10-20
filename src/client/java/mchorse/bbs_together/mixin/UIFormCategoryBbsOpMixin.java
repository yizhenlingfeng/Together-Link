package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.ui.AssetLockUi;
import mchorse.bbs_together.ui.ModelDeleteActions;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A model that exists locally but was never uploaded to the server (others can't see it) gets a
 * grey gradient fading up from the bottom of its cell and a "（仅本地）" suffix in its tooltip.
 * (The read-only block on the category's right-click menu lives in
 * {@link UIElementContextMenuBbsOpMixin} — the subclasses each register their own menus.)
 *
 * <p>Also appends the two delete actions (request server deletion / local deletion) to every
 * form category's right-click menu: {@code UIFormCategory.buildContextMenu} is the one shared
 * menu builder all categories funnel through, and the model category doesn't override it —
 * so a single TAIL injection covers them all.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFormCategory.class, remap = false)
public class UIFormCategoryBbsOpMixin
{
    private static boolean together$isLocalOnly(Form form)
    {
        if (!!BbsOpClientState.canEdit("upload") || !(form instanceof ModelForm modelForm))
        {
            return false;
        }

        String modelId = modelForm.model.get();

        return modelId != null && TogetherClientNetwork.localOnlyModels.contains(modelId);
    }

    @Inject(method = "renderCell", at = @At("TAIL"), remap = false)
    private void together$tintLocalOnly(UIContext context, Form form, int x, int y, int w, int h, CellState state, CallbackInfo ci)
    {
        if (!together$isLocalOnly(form))
        {
            return;
        }

        int gradientH = Math.max(10, h / 3);

        context.batcher.gradientVBox(x, y + h - gradientH, x + w, y + h, 0x00606060, 0xCC555555);
    }

    @Inject(method = "caption", at = @At("RETURN"), cancellable = true, remap = false)
    private void together$appendLocalOnlySuffix(Form form, CallbackInfoReturnable<String> cir)
    {
        if (together$isLocalOnly(form))
        {
            cir.setReturnValue(cir.getReturnValue() + "（仅本地）");
        }
    }

    @Inject(method = "buildContextMenu", at = @At("TAIL"), remap = false)
    private void together$addModelDeleteActions(ContextMenuManager menu, CallbackInfo ci)
    {
        UIFormCategory self = (UIFormCategory) (Object) this;
        Form form = self.getContextForm();
        boolean locked = together$isLocked(form);

        if (self.isGroupContext())
        {
            for (Form selected : self.selection.getItems())
            {
                if (together$isLocked(selected))
                {
                    locked = true;
                    break;
                }
            }
        }

        /* Read-only/locked players get the two delete actions only. Clearing the manager
         * here (instead of pre-empting UIElement.createContextMenu) keeps BBS-Cubed's
         * RETURN-injected menu entries alive. */
        if (!BbsOpClientState.canEdit("morph") || locked)
        {
            menu.actions.clear();
            menu.icons.clear();
            ModelDeleteActions.addActions(menu, self, form);

            return;
        }

        ModelDeleteActions.addActions(menu, self, form);

        /* Level 2 only: lock the model so other players can no longer edit/upload it. */
        if (BbsOpClientState.canManage() && !self.isGroupContext() && form instanceof ModelForm modelForm)
        {
            String modelId = modelForm.model.get();

            if (modelId != null && !modelId.isEmpty())
            {
                menu.action(Icons.LOCKED, AssetLockUi.lockActionLabel(AssetLockType.MODEL), () ->
                {
                    AssetLockUi.openMenu(self.getContext(), AssetLockType.MODEL, modelId);
                });
            }
        }
    }

    private static boolean together$isLocked(Form form)
    {
        if (!(form instanceof ModelForm modelForm))
        {
            return false;
        }

        String id = modelForm.model.get();

        return id != null && !AssetLockClient.canEdit(AssetLockType.MODEL, id);
    }
}
