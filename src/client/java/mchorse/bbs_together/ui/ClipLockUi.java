package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.context.MenuIcon;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_together.locks.AssetLockMode;
import mchorse.bbs_together.locks.ClipLockClient;
import mchorse.bbs_together.locks.ClipLockData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * The clip lock button of the camera timeline's right-click icon bar. It sits in
 * the COMMON zone, which is registered last, so it lands right after the presets
 * button (the ≡ icon) — the clip equivalent of the film lock in the film panel.
 *
 * <p>The button only appears for level-2 admins, exactly like the film and model
 * lock menus: a lock you are allowed to edit stays invisible to you.</p>
 */
@Environment(EnvType.CLIENT)
public final class ClipLockUi
{
    private ClipLockUi()
    {}

    public static IKey key(String id)
    {
        return L10n.lang(id);
    }

    /** Called by the UIClips mixin from the extra context menu consumer added at construction. */
    public static void install(ContextMenuManager menu, UIClips clips, IUIClipsDelegate delegate)
    {
        UIContext context = clips.getContext();

        if (context == null || !ClipLockClient.canManage())
        {
            return;
        }

        Clip clip = clipAt(clips, context.mouseX, context.mouseY);

        if (clip == null)
        {
            return;
        }

        ClipLockData lock = ClipLockClient.get(clip);

        menu.icons.add(new MenuIcon(lock != null ? Icons.LOCKED : Icons.UNLOCKED, key("bbs_together.clip_lock.menu.label"), MenuVerb.Slot.COMMON, () -> openMenu(clips, delegate, clip)));
    }

    private static Clip clipAt(UIClips clips, int mouseX, int mouseY)
    {
        if (clips.getClips() == null)
        {
            return null;
        }

        int layer = clips.fromLayerY(mouseY);

        if (layer < 0)
        {
            return null;
        }

        int tick = (int) Math.floor(clips.fromGraphX(mouseX));

        return clips.getClips().getClipAt(tick, layer);
    }

    private static void openMenu(UIClips clips, IUIClipsDelegate delegate, Clip clip)
    {
        if (clip == null || !ClipLockClient.canManage())
        {
            return;
        }

        UIContext context = clips.getContext();

        if (context == null)
        {
            return;
        }

        context.replaceContextMenu((menu) ->
        {
            ClipLockData lock = ClipLockClient.get(clip);

            menu.action(Icons.LOCKED, key("bbs_together.clip_lock.menu.all"), () -> apply(clips, delegate, clip, AssetLockMode.ALL, Collections.emptyMap()));

            int whitelist = lock == null ? 0 : lock.whitelist.size();
            int blacklist = lock == null ? 0 : lock.blacklist.size();

            menu.action(Icons.USER, members(key("bbs_together.clip_lock.menu.whitelist"), key("bbs_together.clip_lock.menu.whitelist_edit"), whitelist), () -> openPicker(clips, delegate, clip, AssetLockMode.WHITELIST));
            menu.action(Icons.USER, members(key("bbs_together.clip_lock.menu.blacklist"), key("bbs_together.clip_lock.menu.blacklist_edit"), blacklist), () -> openPicker(clips, delegate, clip, AssetLockMode.BLACKLIST));

            if (lock != null)
            {
                menu.action(Icons.UNLOCKED, key("bbs_together.clip_lock.menu.unlock"), () -> apply(clips, delegate, clip, null, Collections.emptyMap()));
            }
        });
    }

    private static IKey members(IKey empty, IKey count, int size)
    {
        return size > 0 ? count.format(size) : empty;
    }

    private static void openPicker(UIClips clips, IUIClipsDelegate delegate, Clip clip, AssetLockMode mode)
    {
        UIContext context = clips.getContext();

        if (context == null)
        {
            return;
        }

        ClipLockData lock = ClipLockClient.get(clip);
        Map<UUID, String> initial = lock == null ? Collections.emptyMap() : lock.listFor(mode);
        IKey title = key(mode == AssetLockMode.WHITELIST ? "bbs_together.clip_lock.picker.whitelist" : "bbs_together.clip_lock.picker.blacklist");

        UIOverlay.addOverlay(context, new UIAssetLockPlayersPanel(title, initial, (selected) -> apply(clips, delegate, clip, mode, selected)), 320, 0.5F);
    }

    private static void apply(UIClips clips, IUIClipsDelegate delegate, Clip clip, AssetLockMode mode, Map<UUID, String> members)
    {
        ClipLockClient.apply(delegate.getFilm(), clip, mode, members);
        delegate.fillData();

        if (delegate instanceof UIClipsPanel panel && panel.filmPanel != null)
        {
            /* Save right away so the lock reaches the other players and the server snapshot. */
            panel.filmPanel.save();
        }
    }
}
