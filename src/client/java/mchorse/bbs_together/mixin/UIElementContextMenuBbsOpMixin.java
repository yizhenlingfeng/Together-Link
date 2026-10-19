package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.context.UIContextMenu;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.ui.ModelDeleteActions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-only (level 0) lock on the form list's right-click menu. UIFormCategory AND its
 * subclasses (model/recent/user categories) each register their own context menu lambdas,
 * so the single choke point every one of them funnels through is UIElement's
 * {@code createContextMenu}: returning null for any form-category element under read-only
 * suppresses every menu variant (edit, export model, add/remove/rename category, ...).
 * Picking and applying a disguise (left-click selection) stays untouched.
 *
 * <p>Exception: the two delete actions (request server deletion / local deletion) stay
 * available under read-only — they don't grant any edit power (the server gates the
 * request behind an admin's approval, the local one only ever touches local files).
 * Read-only players get a minimal menu containing just those two actions for the model
 * under the cursor, or nothing when no model is under it.</p>
 *
 * <p>A single locked model gets the same minimal menu as level 0, even when the player has
 * the morph domain. That is what makes "locked disguise model" mean the same read-only state
 * a level-0 player sees.</p>
 */
@Mixin(value = UIElement.class, remap = false)
public class UIElementContextMenuBbsOpMixin
{
    @Inject(method = "createContextMenu", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$blockFormCategoryMenus(UIContext context, CallbackInfoReturnable<UIContextMenu> cir)
    {
        if (!((Object) this instanceof UIFormCategory category))
        {
            return;
        }

        boolean locked = together$isLocked(category.getContextForm());

        if (category.isGroupContext())
        {
            for (Form form : category.selection.getItems())
            {
                if (together$isLocked(form))
                {
                    locked = true;
                    break;
                }
            }
        }

        if (BbsOpClientState.canEdit("morph") && !locked)
        {
            return;
        }

        ContextMenuManager manager = new ContextMenuManager();

        ModelDeleteActions.addActions(manager, category, category.getContextForm());

        /* create() returns null when nothing matched — the same full suppression as before */
        cir.setReturnValue(manager.create());
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
