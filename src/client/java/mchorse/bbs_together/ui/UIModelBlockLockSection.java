package mchorse.bbs_together.ui;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.locks.AssetLock;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockManager;
import mchorse.bbs_together.locks.AssetLockMode;
import mchorse.bbs_together.locks.AssetLockType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The lock controls added under the model block panel's "look at target" toggle.
 * It only exists for BBS level-2 players.
 *
 * <p>Layout: one "lock editing" switch; once it is on, two columns appear below it.
 * The left column enables the whitelist and opens its player picker, the right
 * column does the same for the blacklist. Both pickers are the shared multi-select
 * player panel. A column that is not enabled has its member button greyed out and
 * disabled, so which list is in force is visible at a glance.</p>
 */
@Environment(EnvType.CLIENT)
public class UIModelBlockLockSection extends UIElement
{
    private static final int COLLAPSED = 14;
    private static final int EXPANDED = 64;

    private final Supplier<ModelBlockEntity> block;
    private final Runnable onResize;

    private final UIToggle lockToggle;
    private final UIElement lists;
    private final UIToggle whitelistToggle;
    private final UIToggle blacklistToggle;
    private final UIButton whitelistButton;
    private final UIButton blacklistButton;

    private boolean suppress;
    private boolean lastVisible;
    private int lastHeight = -1;

    public UIModelBlockLockSection(Supplier<ModelBlockEntity> block, Runnable onResize)
    {
        this.block = block;
        this.onResize = onResize;

        this.column(2).vertical().stretch();
        this.h(COLLAPSED);

        this.lockToggle = new UIToggle(AssetLockUi.key("bbs_together.locks.model_block.lock"), (b) -> this.toggleLock(b.getValue()));
        this.lockToggle.tooltip(AssetLockUi.key("bbs_together.locks.model_block.all_hint"));
        this.whitelistToggle = new UIToggle(AssetLockUi.key("bbs_together.locks.model_block.whitelist"), (b) -> this.toggleWhitelist(b.getValue()));
        this.blacklistToggle = new UIToggle(AssetLockUi.key("bbs_together.locks.model_block.blacklist"), (b) -> this.toggleBlacklist(b.getValue()));
        this.whitelistButton = new UIButton(AssetLockUi.key("bbs_together.locks.model_block.whitelist_members_off"), (b) -> this.openList(AssetLockMode.WHITELIST));
        this.blacklistButton = new UIButton(AssetLockUi.key("bbs_together.locks.model_block.blacklist_members_off"), (b) -> this.openList(AssetLockMode.BLACKLIST));

        UIElement whitelistColumn = UI.column(2, this.whitelistToggle, this.whitelistButton);
        UIElement blacklistColumn = UI.column(2, this.blacklistToggle, this.blacklistButton);

        whitelistColumn.h(44);
        blacklistColumn.h(44);

        this.lists = UI.row(4, whitelistColumn, blacklistColumn);
        this.lists.h(44);
        this.lists.setVisible(false);

        this.add(this.lockToggle, this.lists);
    }

    private String blockKey()
    {
        ModelBlockEntity entity = this.block.get();

        if (entity == null)
        {
            return null;
        }

        return AssetLockManager.modelBlockId(entity.getWorld(), entity.getPos());
    }

    private void toggleLock(boolean value)
    {
        if (this.suppress)
        {
            return;
        }

        String key = this.blockKey();

        if (key == null)
        {
            return;
        }

        if (value)
        {
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, AssetLockMode.ALL, Collections.emptyMap());
        }
        else
        {
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, null, null);
        }

        this.refresh();
    }

    private void toggleWhitelist(boolean value)
    {
        if (this.suppress)
        {
            return;
        }

        String key = this.blockKey();

        if (key == null)
        {
            return;
        }

        if (value)
        {
            this.blacklistToggle.setValue(false);
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, AssetLockMode.WHITELIST, this.members(AssetLockMode.WHITELIST));
        }
        else if (!this.blacklistToggle.getValue())
        {
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, AssetLockMode.ALL, Collections.emptyMap());
        }

        this.refresh();
    }

    private void toggleBlacklist(boolean value)
    {
        if (this.suppress)
        {
            return;
        }

        String key = this.blockKey();

        if (key == null)
        {
            return;
        }

        if (value)
        {
            this.whitelistToggle.setValue(false);
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, AssetLockMode.BLACKLIST, this.members(AssetLockMode.BLACKLIST));
        }
        else if (!this.whitelistToggle.getValue())
        {
            AssetLockUi.apply(AssetLockType.MODEL_BLOCK, key, AssetLockMode.ALL, Collections.emptyMap());
        }

        this.refresh();
    }

    private Map<UUID, String> members(AssetLockMode mode)
    {
        String key = this.blockKey();

        if (key == null)
        {
            return Collections.emptyMap();
        }

        AssetLock lock = AssetLockClient.get(AssetLockType.MODEL_BLOCK, key);

        return lock == null ? Collections.emptyMap() : lock.listFor(mode);
    }

    private void openList(AssetLockMode mode)
    {
        String key = this.blockKey();
        UIContext context = this.getContext();

        if (key == null || context == null)
        {
            return;
        }

        AssetLock lock = AssetLockClient.get(AssetLockType.MODEL_BLOCK, key);

        AssetLockUi.openPicker(context, AssetLockType.MODEL_BLOCK, key, mode, lock);
    }

    /** Keep the controls in sync with the selected block and the server push. */
    public void refresh()
    {
        boolean visible = AssetLockClient.canManage() && this.block.get() != null;

        if (!visible)
        {
            if (this.isVisible())
            {
                this.setVisible(false);
                this.changed();
            }

            this.lastVisible = false;

            return;
        }

        if (!this.isVisible())
        {
            this.setVisible(true);
        }

        String key = this.blockKey();

        if (key == null)
        {
            return;
        }

        AssetLock lock = AssetLockClient.get(AssetLockType.MODEL_BLOCK, key);
        boolean locked = lock != null;

        this.suppress = true;
        this.lockToggle.setValue(locked);
        this.whitelistToggle.setValue(locked && lock.mode == AssetLockMode.WHITELIST);
        this.blacklistToggle.setValue(locked && lock.mode == AssetLockMode.BLACKLIST);
        this.suppress = false;

        this.lists.setVisible(locked);

        boolean white = this.whitelistToggle.getValue();
        boolean black = this.blacklistToggle.getValue();

        this.whitelistButton.setEnabled(white);
        this.blacklistButton.setEnabled(black);
        this.whitelistButton.textColor(white ? Colors.WHITE : Colors.GRAY, white);
        this.blacklistButton.textColor(black ? Colors.WHITE : Colors.GRAY, black);

        int whitelistCount = lock == null ? 0 : lock.whitelist.size();
        int blacklistCount = lock == null ? 0 : lock.blacklist.size();

        this.whitelistButton.label = white
            ? AssetLockUi.key("bbs_together.locks.model_block.whitelist_members").format(String.valueOf(whitelistCount))
            : AssetLockUi.key("bbs_together.locks.model_block.whitelist_members_off");
        this.blacklistButton.label = black
            ? AssetLockUi.key("bbs_together.locks.model_block.blacklist_members").format(String.valueOf(blacklistCount))
            : AssetLockUi.key("bbs_together.locks.model_block.blacklist_members_off");

        int height = locked ? EXPANDED : COLLAPSED;

        if (this.lastHeight != height)
        {
            this.lastHeight = height;
            this.lastVisible = true;
            this.h(height);
            this.changed();
        }
        else if (!this.lastVisible)
        {
            this.lastVisible = true;
            this.changed();
        }
    }

    private void changed()
    {
        if (this.onResize != null)
        {
            this.onResize.run();
        }
    }
}
