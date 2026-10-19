package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.locks.AssetLock;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockMode;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Shared entry points for the level-2 asset lock UI: the context submenu used by
 * models and films, the player picker, and the small helpers the model block
 * section reads.
 */
@Environment(EnvType.CLIENT)
public final class AssetLockUi
{
    private AssetLockUi()
    {}

    public static IKey key(String id)
    {
        return L10n.lang(id);
    }

    /** "Lock model" / "Lock film", matching the asset being right-clicked. */
    public static IKey lockActionLabel(AssetLockType type)
    {
        return key(type == AssetLockType.FILM ? "bbs_together.locks.menu.lock_film" : "bbs_together.locks.menu.lock_model");
    }

    /**
     * Open the lock submenu (nobody / whitelist / blacklist / unlock). This is what the
     * morph-model menu, the film list menu icon and any future entry point call.
     */
    public static void openMenu(UIContext context, AssetLockType type, String assetId)
    {
        if (context == null || !AssetLockClient.canManage() || type == null || assetId == null)
        {
            return;
        }

        AssetLock lock = AssetLockClient.get(type, assetId);

        context.replaceContextMenu((menu) ->
        {
            menu.action(Icons.LOCKED, key("bbs_together.locks.menu.all"), () ->
            {
                apply(type, assetId, AssetLockMode.ALL, Collections.emptyMap());
            });

            boolean whitelistHasMembers = lock != null && !lock.whitelist.isEmpty();
            boolean blacklistHasMembers = lock != null && !lock.blacklist.isEmpty();

            if (whitelistHasMembers && blacklistHasMembers)
            {
                /* Both lists carry players only after older data / a mode switch. Keep the
                 * active mode's editor visible and let the other one be cleared normally. */
                if (lock.mode == AssetLockMode.WHITELIST)
                {
                    menu.action(Icons.USER, whitelistLabel(lock), () ->
                    {
                        openPicker(context, type, assetId, AssetLockMode.WHITELIST, lock);
                    });
                }
                else
                {
                    menu.action(Icons.USER, blacklistLabel(lock), () ->
                    {
                        openPicker(context, type, assetId, AssetLockMode.BLACKLIST, lock);
                    });
                }
            }
            else if (whitelistHasMembers)
            {
                menu.action(Icons.USER, whitelistLabel(lock), () ->
                {
                    openPicker(context, type, assetId, AssetLockMode.WHITELIST, lock);
                });
            }
            else if (blacklistHasMembers)
            {
                menu.action(Icons.USER, blacklistLabel(lock), () ->
                {
                    openPicker(context, type, assetId, AssetLockMode.BLACKLIST, lock);
                });
            }
            else
            {
                menu.action(Icons.USER, key("bbs_together.locks.menu.whitelist"), () ->
                {
                    openPicker(context, type, assetId, AssetLockMode.WHITELIST, lock);
                });

                menu.action(Icons.USER, key("bbs_together.locks.menu.blacklist"), () ->
                {
                    openPicker(context, type, assetId, AssetLockMode.BLACKLIST, lock);
                });
            }

            if (lock != null)
            {
                menu.action(Icons.UNLOCKED, key("bbs_together.locks.menu.unlock"), () ->
                {
                    AssetLockClient.removeLocal(type, assetId);
                    TogetherClientNetwork.sendAssetLock(type, assetId, null, null);
                });
            }
        });
    }

    private static IKey whitelistLabel(AssetLock lock)
    {
        return key("bbs_together.locks.menu.whitelist_edit").format(String.valueOf(lock.whitelist.size()));
    }

    private static IKey blacklistLabel(AssetLock lock)
    {
        return key("bbs_together.locks.menu.blacklist_edit").format(String.valueOf(lock.blacklist.size()));
    }

    /** Open the multi-select player picker for one of the two list modes. */
    public static void openPicker(UIContext context, AssetLockType type, String assetId, AssetLockMode mode, AssetLock lock)
    {
        if (context == null || !AssetLockClient.canManage() || (mode != AssetLockMode.WHITELIST && mode != AssetLockMode.BLACKLIST))
        {
            return;
        }

        Map<UUID, String> initial = lock == null ? Collections.emptyMap() : lock.listFor(mode);
        /* Picker labels live in the vanilla language table (assets/bbs_together/lang),
         * not in BBS' strings table, so they must use CoopL10n. */
        IKey title = CoopL10n.key(mode == AssetLockMode.WHITELIST
            ? "bbs_together.locks.picker.whitelist.title"
            : "bbs_together.locks.picker.blacklist.title");

        UIOverlay.addOverlay(context, new UIAssetLockPlayersPanel(title, initial, (selected) ->
        {
            apply(type, assetId, mode, selected);
        }), 320, 0.5F);
    }

    /** Push a lock change locally for instant UI feedback and to the server. */
    public static void apply(AssetLockType type, String assetId, AssetLockMode mode, Map<UUID, String> members)
    {
        if (type == null || assetId == null)
        {
            return;
        }

        if (mode == null)
        {
            AssetLockClient.removeLocal(type, assetId);
        }
        else
        {
            Map<UUID, String> list = members == null ? Collections.emptyMap() : members;

            AssetLockClient.applyLocal(type, assetId, mode, list);
        }

        TogetherClientNetwork.sendAssetLock(type, assetId, mode, members);
    }
}
