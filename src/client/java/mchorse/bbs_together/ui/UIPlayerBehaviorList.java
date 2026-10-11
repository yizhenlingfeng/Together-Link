package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.List;
import java.util.function.Consumer;

/**
 * The left column: players currently on the server, grouped by their (persisted) BBS
 * permission level — level 2 admins on top, then level 1 editors, then level 0 viewers,
 * with the pseudo "all players" row pinned above everything. The groups come straight
 * from the server snapshot, so a player lands back in their group automatically every
 * time they join. Right-clicking a row offers BBS level management (managers only),
 * domain toggles for restricted level-1 editors, vanilla OP management and log removal
 * (with a confirmation step).
 */
public class UIPlayerBehaviorList extends UIList<PlayerRecord>
{
    /** Group id of the pinned "all players" row (higher than any real level). */
    public static final int GROUP_ALL = 3;

    /** Pseudo record standing for "no filter". */
    public static PlayerRecord all()
    {
        PlayerRecord record = header(GROUP_ALL, "bbs_together.behaviors.all_players");

        record.everEdited = true;

        return record;
    }

    /** Pseudo record rendering as a group caption above its level's players. */
    public static PlayerRecord header(int level, String key)
    {
        PlayerRecord record = new PlayerRecord();

        record.name = L10n.lang(key).get();
        record.groupHeader = true;
        record.bbsLevel = level;

        return record;
    }

    private final Consumer<PlayerRecord> callback;

    public UIPlayerBehaviorList(Consumer<PlayerRecord> callback)
    {
        super((l) -> callback.accept(pickFirst(l)));

        this.callback = callback;
        this.scroll.scrollItemSize = 20;
        this.sorting();
        this.context(this::fillContextMenu);
    }

    private static PlayerRecord pickFirst(List<PlayerRecord> list)
    {
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Grouped by BBS level (2, then 1, then 0, the "all players" row above all of that);
     * within a group, edited players first (by name), and group caption rows sit on top
     * of their group's members.
     */
    public static int compare(PlayerRecord a, PlayerRecord b)
    {
        int byGroup = Integer.compare(b.bbsLevel, a.bbsLevel);

        if (byGroup != 0)
        {
            return byGroup;
        }

        if (a.groupHeader != b.groupHeader)
        {
            return a.groupHeader ? -1 : 1;
        }

        if (a.id == null || b.id == null)
        {
            return a.name.compareToIgnoreCase(b.name);
        }

        int edited = -Boolean.compare(a.everEdited, b.everEdited);

        return edited != 0 ? edited : a.name.compareToIgnoreCase(b.name);
    }

    @Override
    protected boolean sortElements()
    {
        this.list.sort(UIPlayerBehaviorList::compare);

        return true;
    }

    @Override
    protected String elementToString(UIContext context, int i, PlayerRecord element)
    {
        return element.name;
    }

    @Override
    public void renderElementPart(UIContext context, PlayerRecord element, int i, int x, int y, boolean hover, boolean selected)
    {
        if (element.groupHeader)
        {
            renderHeader(context, element, x, y);

            return;
        }

        int s = this.scroll.scrollItemSize;
        int face = s - 6;
        int faceY = y + (s - face) / 2;
        int textX = x + 4;
        boolean dimmed = element.id != null && !element.everEdited;

        if (element.id != null)
        {
            UIPlayerFaceRenderer.draw(context.batcher, element.name, element.id, textX, faceY, face);

            /* A player who never edited / uploaded / downloaded fades a little */
            if (dimmed)
            {
                context.batcher.box(textX, faceY, textX + face, faceY + face, 0x88555555);
            }

            textX += face + 5;
        }

        String label = element.name;
        String suffix = element.offline ? " " + L10n.lang("bbs_together.behaviors.offline").get() : "";
        int width = this.area.w - (textX - x) - 8;
        int suffixWidth = suffix.isEmpty() ? 0 : context.batcher.getFont().getWidth(suffix);

        label = crop(context.batcher.getFont(), label, Math.max(8, width - suffixWidth));

        int textY = y + (s - context.batcher.getFont().getHeight()) / 2;
        int textColor = hover || selected ? Colors.WHITE : (dimmed ? Colors.GRAY : Colors.LIGHTER_GRAY);

        context.batcher.textShadow(label, textX, textY, textColor);

        if (!suffix.isEmpty())
        {
            context.batcher.textShadow(suffix, textX + context.batcher.getFont().getWidth(label) + 1, textY, Colors.GRAY);
        }
    }

    /** A slim caption row: level label in accent gray, left aligned like the player names. */
    private void renderHeader(UIContext context, PlayerRecord element, int x, int y)
    {
        int s = this.scroll.scrollItemSize;
        int textY = y + (s - context.batcher.getFont().getHeight()) / 2;

        context.batcher.textShadow(element.name, x + 4, textY, Colors.LIGHTER_GRAY);
    }

    /** Right click selects the row under the cursor, so the menu speaks of that row. */
    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 1 && this.area.isInside(context))
        {
            int index = this.indexAt(this.contentX(context), this.contentY(context));

            if (index >= 0 && index < this.visible().size())
            {
                PlayerRecord record = this.visible().get(index);

                this.selection.set(record, null);
                this.fireCallback();
            }
        }

        return super.subMouseClicked(context);
    }

    /** Whether the domain is currently editable for this (level-1) player; null grants = all. */
    private static boolean isDomainAllowed(PlayerRecord record, BbsOpDomain domain)
    {
        return record.bbsGrants == null || record.bbsGrants.contains(domain.id);
    }

    private void fillContextMenu(ContextMenuManager menu)
    {
        PlayerRecord record = this.getCurrentFirst();

        if (record == null || record.id == null)
        {
            return;
        }

        /* BBS permission management: BBS level-2 admins only (vanilla OPs use /bbsop; server re-checks). */
        if (BbsOpClientState.canManage())
        {
            menu.action(Icons.EDIT, L10n.lang("bbs_together.bbsop.context.level_menu"), () ->
            {
                this.getContext().replaceContextMenu((sub) ->
                {
                    for (int level = 2; level >= 0; level--)
                    {
                        int target = level;

                        String label = L10n.lang("bbs_together.bbsop.context.set_level").get()
                            .replace("{level}", L10n.lang("bbs_together.bbsop.level." + level).get());

                        sub.action(Icons.EDIT, IKey.constant(label), () ->
                        {
                            TogetherClientNetwork.sendSetBbsOp(record.id, target);
                        });
                    }
                });
            });

            /* Domain toggles only make sense for level-1 editors. */
            if (record.bbsLevel == 1)
            {
                menu.action(Icons.LIST, L10n.lang("bbs_together.bbsop.context.domain_menu"), () ->
                {
                    this.getContext().replaceContextMenu((sub) ->
                    {
                        for (BbsOpDomain domain : BbsOpDomain.values())
                        {
                            boolean allowed = isDomainAllowed(record, domain);

                            String label = L10n.lang("bbs_together.bbsop.context.domain").get()
                                .replace("{domain}", L10n.lang("bbs_together.bbsop.domain." + domain.id).get())
                                .replace("{state}", L10n.lang(allowed ? "bbs_together.bbsop.context.allowed" : "bbs_together.bbsop.context.denied").get());

                            sub.action(allowed ? Icons.ADD : Icons.CLOSE, IKey.constant(label), () ->
                            {
                                /* Resolve the toggle against the latest snapshot at click time,
                                 * not the state captured when the menu was built. */
                                PlayerRecord latest = TogetherClientNetwork.behaviorPlayers.get(record.id);
                                boolean nowAllowed = latest == null ? allowed : isDomainAllowed(latest, domain);

                                TogetherClientNetwork.sendSetBbsGrant(record.id, domain.id, !nowAllowed);
                            });
                        }
                    });
                });
            }
        }

        if (TogetherClientNetwork.isOperator)
        {
            /* An admin can neither demote their own vanilla OP status (dragging one's own row
             * to another level group is blocked in {@link #dragPayload} for the same reason). */
            if (!isSelf(record))
            {
                if (record.op)
                {
                    menu.action(Icons.CLOSE, L10n.lang("bbs_together.behaviors.context.remove_op"), () -> TogetherClientNetwork.sendSetOperator(record.id, false));
                }
                else
                {
                    menu.action(Icons.ADD, L10n.lang("bbs_together.behaviors.context.make_op"), () -> TogetherClientNetwork.sendSetOperator(record.id, true));
                }
            }

            /* Wipe this player's log, with a confirmation step */
            menu.action(Icons.TRASH, L10n.lang("bbs_together.behaviors.context.remove_logs"), () ->
            {
                PlayerRecord target = record;

                this.getContext().replaceContextMenu((confirm) ->
                {
                    confirm.action(Icons.TRASH, L10n.lang("bbs_together.behaviors.context.remove_logs_confirm"), () ->
                    {
                        TogetherClientNetwork.sendRemovePlayerLogs(target.id);
                    });
                    confirm.action(Icons.CLOSE, L10n.lang("bbs_together.behaviors.context.remove_logs_cancel"), () ->
                    {
                        this.getContext().closeContextMenu();
                    });
                });
            });
        }
    }

    /** Whether this row is the local player themself. */
    private static boolean isSelf(PlayerRecord record)
    {
        return record != null && record.id != null
            && net.minecraft.client.MinecraftClient.getInstance().player != null
            && record.id.equals(net.minecraft.client.MinecraftClient.getInstance().player.getUuid());
    }

    /* Drag & drop between level groups */

    /**
     * Only managers may drag players, and only player rows can be dragged — group captions
     * and the "all players" row stay put. Admins can't drag their own row (no self-demote
     * by drag). Returning null disables dragging entirely.
     */
    @Override
    protected List<PlayerRecord> dragPayload(PlayerRecord item)
    {
        if (item == null || item.groupHeader || isSelf(item) || !BbsOpClientState.canManage())
        {
            return null;
        }

        return super.dragPayload(item);
    }

    /**
     * Any real level group takes a dropped player: the caption rows are the natural
     * targets, and dropping onto another player means "same level as them".
     */
    @Override
    protected boolean acceptsDrop(PlayerRecord element)
    {
        return element != null && element.bbsLevel >= 0 && element.bbsLevel <= 2;
    }

    /**
     * A dropped player always lands <em>into</em> a group — reordering between rows makes
     * no sense here (the list is sorted by level anyway), so there is no insertion caret:
     * either the cursor is over a row of a real level group, or the drop does nothing.
     */
    @Override
    protected void reportDropTarget(int x, int y)
    {
        int index = this.indexAt(x, y);

        if (index != -1)
        {
            PlayerRecord row = this.visible().get(index);

            if (this.acceptsDrop(row) && !this.drag.isDragging(row))
            {
                this.drag.setTarget(row);

                return;
            }
        }
    }

    /** Dropping a player onto a level group sets their BBS level to that group's (server re-checks). */
    @Override
    protected void onDrop(Object target, List<PlayerRecord> items)
    {
        if (!(target instanceof PlayerRecord) || !BbsOpClientState.canManage())
        {
            return;
        }

        int level = ((PlayerRecord) target).bbsLevel;

        if (level < 0 || level > 2)
        {
            return;
        }

        for (PlayerRecord item : items)
        {
            if (item != null && item.id != null && !item.groupHeader && !isSelf(item) && item.bbsLevel != level)
            {
                TogetherClientNetwork.sendSetBbsOp(item.id, level);
            }
        }
    }

    /** Cut a string down until it fits the given width. */
    static String crop(mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font, String value, int maxWidth)
    {
        if (font.getWidth(value) <= maxWidth)
        {
            return value;
        }

        while (value.length() > 1 && font.getWidth(value + "…") > maxWidth)
        {
            value = value.substring(0, value.length() - 1);
        }

        return value + "…";
    }
}
