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

        if (KeyframeLockClient.film(view) == null)
        {
            return;
        }

        List<KeyframeLockClient.HitRecord> hits = KeyframeLockClient.hits(view, context.mouseX, context.mouseY);
        boolean manage = KeyframeLockClient.canOperateLocks();

        /* The button is present for everyone: a closed padlock when the locks in view are
         * enforced on this player, an open one when they are not. Only managers can press it. */
        MenuIcon icon = new MenuIcon(hits.isEmpty() || manage ? Icons.UNLOCKED : Icons.LOCKED, key("bbs_together.keyframe_lock.menu.label"), MenuVerb.Slot.COMMON, () ->
        {
            if (manage)
            {
                openMenu(view, hits);
            }
        });

        icon.enabled = manage;

        menu.icons.add(icon);
    }

    private static void openMenu(UIKeyframes view, List<KeyframeLockClient.HitRecord> hits)
    {
        UIContext context = view.getContext();

        if (context == null || !KeyframeLockClient.canOperateLocks())
        {
            return;
        }

        context.replaceContextMenu((menu) ->
        {
            /* BBS owns the menu width: without a row of its own the bar is as wide as its
             * buttons and a label would stick out past the right edge. One row is always
             * written, so it reads as the menu's caption and not as a button that lies.
             * Plain action (no colour overload): the colour overload builds a
             * ColorfulContextAction whose renderBackground paints a 22px swatch into the
             * icon column, which showed up as a stray white block next to the caption. */
            KeyframeLockClient.Selection selection = KeyframeLockClient.selection(view);
            IKey status = selection.problem() == null
                ? key("bbs_together.keyframe_lock.status.ready").format(selection.keys().size())
                : key("bbs_together.keyframe_lock.status." + selection.problem());

            menu.action(Icons.NONE, status, () ->
            {
                if (selection.ok())
                {
                    KeyframeLockClient.create(view, selection);
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
