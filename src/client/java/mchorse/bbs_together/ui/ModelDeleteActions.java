package mchorse.bbs_together.ui;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The two delete actions of the model list's right-click menu: request a
 * server-side deletion (logged as a pending behavior an admin approves or
 * denies from the behavior list), and delete the local copy only — with an
 * extra warning step while asset auto sync is on, since the model would
 * otherwise be re-synced back after deletion.
 */
@Environment(EnvType.CLIENT)
public class ModelDeleteActions
{
    /** Append the two actions for the model under the context menu's cursor. */
    public static void addActions(ContextMenuManager menu, UIFormCategory category, Form form)
    {
        if (!(form instanceof ModelForm) || category.isGroupContext())
        {
            return;
        }

        String modelId = ((ModelForm) form).model.get();

        if (modelId == null || modelId.isEmpty())
        {
            return;
        }

        menu.action(Icons.REMOVE, L10n.lang("bbs_together.models.context.request_delete"), () -> confirmRequest(category, modelId));
        menu.action(Icons.TRASH, L10n.lang("bbs_together.models.context.local_delete"), () -> confirmLocal(category, modelId));
    }

    private static void confirmRequest(UIFormCategory category, String modelId)
    {
        category.getContext().replaceContextMenu((confirm) ->
        {
            confirm.action(Icons.REMOVE, L10n.lang("bbs_together.models.context.request_confirm").format(modelId), () ->
            {
                TogetherClientNetwork.sendModelDeleteRequest(modelId);
            });
            confirm.action(Icons.CLOSE, L10n.lang("bbs_together.models.context.cancel"), () ->
            {
                category.getContext().closeContextMenu();
            });
        });
    }

    /**
     * The confirmation menu for a local delete. One level only: while asset auto
     * sync is on the menu directly offers "disable auto sync and delete" (the
     * model would be re-synced back otherwise) besides the plain delete; with
     * auto sync off it's a plain confirm.
     */
    private static void confirmLocal(UIFormCategory category, String modelId)
    {
        category.getContext().replaceContextMenu((confirm) ->
        {
            if (isAutoSyncOn())
            {
                confirm.action(Icons.TRASH, L10n.lang("bbs_together.models.context.autosync_off_delete"), () ->
                {
                    setAutoSync(false);
                    TogetherClientNetwork.deleteLocalModelFolder(modelId);
                });
                confirm.action(Icons.TRASH, L10n.lang("bbs_together.models.context.local_delete_only"), () ->
                {
                    TogetherClientNetwork.deleteLocalModelFolder(modelId);
                });
            }
            else
            {
                confirm.action(Icons.TRASH, L10n.lang("bbs_together.models.context.local_delete_confirm").format(modelId), () ->
                {
                    TogetherClientNetwork.deleteLocalModelFolder(modelId);
                });
            }

            confirm.action(Icons.CLOSE, L10n.lang("bbs_together.models.context.cancel"), () ->
            {
                category.getContext().closeContextMenu();
            });
        });
    }

    private static boolean isAutoSyncOn()
    {
        return TogetherSettings.assetAutoSync != null && Boolean.TRUE.equals(TogetherSettings.assetAutoSync.get());
    }

    private static void setAutoSync(boolean value)
    {
        if (TogetherSettings.assetAutoSync != null)
        {
            TogetherSettings.assetAutoSync.set(Boolean.valueOf(value));
        }
    }
}
