package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.network.TogetherClientNetwork;

import java.util.List;
import java.util.function.Consumer;

/**
 * The left column: players currently on the server — edited ones first, then
 * never-edited ones with a dimmed face and name at the bottom. The first row
 * is "all players". Right-clicking a row offers OP management and log removal
 * (with a confirmation step).
 */
public class UIPlayerBehaviorList extends UIList<PlayerRecord>
{
    /** Pseudo record standing for "no filter". */
    public static PlayerRecord all()
    {
        PlayerRecord record = new PlayerRecord();

        record.name = L10n.lang("bbs_together.behaviors.all_players").get();
        record.everEdited = true;

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

    /** Edited players first (by name), then the never-edited ones, "all players" pinned on top. */
    public static int compare(PlayerRecord a, PlayerRecord b)
    {
        if (a.id == null)
        {
            return -1;
        }

        if (b.id == null)
        {
            return 1;
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

    private void fillContextMenu(ContextMenuManager menu)
    {
        PlayerRecord record = this.getCurrentFirst();

        if (record == null || record.id == null || !TogetherClientNetwork.isOperator)
        {
            return;
        }

        if (record.op)
        {
            menu.action(Icons.CLOSE, L10n.lang("bbs_together.behaviors.context.remove_op"), () -> TogetherClientNetwork.sendSetOperator(record.id, false));
        }
        else
        {
            menu.action(Icons.ADD, L10n.lang("bbs_together.behaviors.context.make_op"), () -> TogetherClientNetwork.sendSetOperator(record.id, true));
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
