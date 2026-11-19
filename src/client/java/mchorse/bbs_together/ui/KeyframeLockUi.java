package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.context.MenuIcon;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.locks.KeyframeLockClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keyframe-lock entry of the keyframe editor's right-click icon bar. It is added
 * through a second context consumer, so it lands after BBS' own icons; a COMMON
 * slot icon then renders right of the presets button.
 */
@Environment(EnvType.CLIENT)
public final class KeyframeLockUi
{
    private KeyframeLockUi()
    {}

    public static IKey key(String id)
    {
        return L10n.lang(id);
    }

    public static void install(ContextMenuManager menu, UIKeyframes view)
    {
        if (view == null)
        {
            return;
        }

        UIContext context = view.getContext();

        if (context == null)
        {
            return;
        }

        /* Locked keys must never stay in the selection, even for look-only players. */
        KeyframeLockClient.filterLockedSelection(view);

        if (!KeyframeLockClient.canManage() || KeyframeLockClient.film(view) == null)
        {
            return;
        }

        List<KeyframeLockClient.HitRecord> hits = KeyframeLockClient.hits(view, context.mouseX, context.mouseY);

        menu.icons.add(new MenuIcon(hits.isEmpty() ? Icons.UNLOCKED : Icons.LOCKED, key("bbs_together.keyframe_lock.menu.label"), MenuVerb.Slot.COMMON, () -> openMenu(view, hits)));
    }

    private static void openMenu(UIKeyframes view, List<KeyframeLockClient.HitRecord> hits)
    {
        UIContext context = view.getContext();

        if (context == null || !KeyframeLockClient.canManage())
        {
            return;
        }

        context.replaceContextMenu((menu) ->
        {
            KeyframeLockClient.Selection selection = KeyframeLockClient.selection(view);

            if (!selection.ok() && selection.problem() != null)
            {
                menu.action(Icons.NONE, key("bbs_together.keyframe_lock.status." + selection.problem()), () -> {});
            }

            menu.action(Icons.LOCKED, key("bbs_together.keyframe_lock.menu.create"), () ->
            {
                if (!selection.ok())
                {
                    return;
                }

                if (KeyframeLockClient.create(view, selection))
                {
                    view.getGraph().clearSelection();
                }
            });

            Set<String> seen = new HashSet<>();

            for (KeyframeLockClient.HitRecord hit : hits)
            {
                if (seen.add(hit.lock().id()))
                {
                    menu.action(Icons.UNLOCKED, key("bbs_together.keyframe_lock.menu.unlock"), () -> KeyframeLockClient.unlock(view, hit.owner(), hit.lock().id()));
                }
            }
        });
    }
}
