package mchorse.bbs_together.ui;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.BehaviorEntry;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_mod.l10n.L10n;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * The middle column: one row per logged behavior — type icon, the player's
 * face (OP view only), the description in the current language, and the time
 * on the right. In the time sort the oldest row sits on top and new ones
 * grow at the bottom, chat-log style.
 */
public class UIBehaviorList extends UIList<BehaviorEntry>
{
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    /** Soft colors per type, tuned to sit next to the theme instead of fighting it. */
    private static final int EDIT_COLOR = 0xffd9a521;
    private static final int DOWNLOAD_COLOR = 0xff40a6d9;
    private static final int UPLOAD_COLOR = 0xff59d940;
    private static final int OTHER_COLOR = 0xff888888;

    /** Whether identity may be shown (OP view); the face is skipped otherwise. */
    public boolean showIdentity;

    public UIBehaviorList(Consumer<List<BehaviorEntry>> callback)
    {
        super(callback);

        this.scroll.scrollItemSize = 18;
        this.context(this::fillContextMenu);
    }

    /** Whether this entry is a pending/decided "delete this server model" request. */
    public static boolean isModelDeleteRequest(BehaviorEntry entry)
    {
        return entry.getType() == BehaviorType.OTHER && entry.subType == BehaviorType.SUB_REQUEST_MODEL_DELETE;
    }

    /** Managers (vanilla OP or BBS level 2) decide pending requests; the server re-checks. */
    private void fillContextMenu(ContextMenuManager menu)
    {
        BehaviorEntry entry = this.getCurrentFirst();

        if (entry == null || !isModelDeleteRequest(entry) || !TogetherClientNetwork.isManager)
        {
            return;
        }

        if (!"pending".equals(entry.extra.get("status")))
        {
            return;
        }

        menu.action(Icons.ADD, L10n.lang("bbs_together.behaviors.context.approve_delete").format(entry.target), () ->
        {
            TogetherClientNetwork.sendModelDeleteDecision(entry.id, true);
        });
        menu.action(Icons.CLOSE, L10n.lang("bbs_together.behaviors.context.deny_delete").format(entry.target), () ->
        {
            TogetherClientNetwork.sendModelDeleteDecision(entry.id, false);
        });
    }

    public static Icon typeIcon(BehaviorEntry entry)
    {
        switch (entry.getType())
        {
            case EDIT: return Icons.EDIT;
            case DOWNLOAD: return Icons.DOWNLOAD;
            case UPLOAD: return Icons.UPLOAD;
            default: return Icons.MORE;
        }
    }

    public static int typeColor(BehaviorEntry entry)
    {
        switch (entry.getType())
        {
            case EDIT: return EDIT_COLOR;
            case DOWNLOAD: return DOWNLOAD_COLOR;
            case UPLOAD: return UPLOAD_COLOR;
            default: return OTHER_COLOR;
        }
    }

    /** How many times this behavior repeated (1 for a one-shot entry). */
    public static int occurrenceCount(BehaviorEntry entry)
    {
        return Math.max(1, entry.occurrences.size());
    }

    /** The description for the current language, also what the search matches against. */
    public static String describe(BehaviorEntry entry)
    {
        int items = entry.items.size();

        switch (entry.getType())
        {
            case EDIT:
                if (entry.subType == BehaviorType.SUB_EDIT_FILM)
                {
                    return L10n.lang("bbs_together.behaviors.desc.edit_film").format(entry.target).get();
                }
                else if (entry.subType == BehaviorType.SUB_EDIT_MODEL_BLOCK)
                {
                    return L10n.lang("bbs_together.behaviors.desc.edit_model_block").format(entry.target).get();
                }

                return L10n.lang("bbs_together.behaviors.desc.edit_model").format(entry.target).get();
            case DOWNLOAD:
                return items > 1
                    ? L10n.lang("bbs_together.behaviors.desc.download_multi").format(String.valueOf(items)).get()
                    : L10n.lang("bbs_together.behaviors.desc.download").format(entry.target).get();
            case UPLOAD:
                /* Uploads say only how many went up; what exactly is in the details pane */
                return items > 1
                    ? L10n.lang("bbs_together.behaviors.desc.upload_multi").format(String.valueOf(items)).get()
                    : L10n.lang("bbs_together.behaviors.desc.upload").format(!entry.target.isEmpty() ? entry.target : entry.items.isEmpty() ? "" : entry.items.get(0)).get();
            default:
                if (entry.subType == BehaviorType.SUB_REQUEST_MODEL_DELETE)
                {
                    return L10n.lang("bbs_together.behaviors.desc.request_model_delete").format(entry.target).get();
                }

                return L10n.lang("bbs_together.behaviors.desc.other").get();
        }
    }

    @Override
    protected String elementToString(UIContext context, int i, BehaviorEntry element)
    {
        /* The name joins the haystack in OP view, so the search can find a player too;
         * uploads/downloads also match on their item paths, which only show in the details */
        String text = describe(element);

        if (!element.target.isEmpty() && !text.contains(element.target))
        {
            text += " " + element.target;
        }

        if (element.items.size() > 1 || (element.target.isEmpty() && !element.items.isEmpty()))
        {
            text += " " + String.join(" ", element.items);
        }

        if (this.showIdentity && element.playerName != null && !element.playerName.isEmpty())
        {
            text += " " + element.playerName;
        }

        int occurrences = occurrenceCount(element);

        if (occurrences > 1)
        {
            text += " ×" + occurrences;
        }

        return text;
    }

    @Override
    public void renderElementPart(UIContext context, BehaviorEntry element, int i, int x, int y, boolean hover, boolean selected)
    {
        int s = this.scroll.scrollItemSize;
        int textY = y + (s - context.batcher.getFont().getHeight()) / 2;
        int cx = x + 4;
        Icon icon = typeIcon(element);

        context.batcher.icon(icon, typeColor(element), cx, y + (s - icon.h) / 2);
        cx += icon.w + 3;

        if (this.showIdentity && element.playerName != null && !element.playerName.isEmpty())
        {
            int face = s - 6;
            int faceY = y + (s - face) / 2;

            UIPlayerFaceRenderer.draw(context.batcher, element.playerName, element.playerId, cx, faceY, face);
            cx += face + 4;

            String name = element.playerName;
            int nameWidth = context.batcher.getFont().getWidth(name + " ");

            name = UIPlayerBehaviorList.crop(context.batcher.getFont(), name, Math.min(nameWidth, 60));

            context.batcher.textShadow(name, cx, textY, Colors.LIGHTER_GRAY);
            cx += context.batcher.getFont().getWidth(name) + 6;
        }

        String description = describe(element);
        int occurrences = occurrenceCount(element);

        if (occurrences > 1)
        {
            description += " ×" + occurrences;
        }

        /* A model-delete request wears its approval state: plain text once approved,
         * a cross plus a grey fade and a "rejected" suffix once denied, a gray
         * "pending" suffix while it waits for a manager's decision. */
        boolean request = isModelDeleteRequest(element);
        String status = request ? element.extra.getOrDefault("status", "pending") : null;
        boolean denied = "denied".equals(status);
        boolean approved = "approved".equals(status);
        Icon statusIcon = denied ? Icons.CLOSE : null;
        int statusColor = 0xffd95050;
        String statusSuffix = "";

        if (request && !approved)
        {
            String key = denied ? "bbs_together.behaviors.status.denied" : "bbs_together.behaviors.status.pending";

            statusSuffix = "（" + L10n.lang(key).get() + "）";
        }

        int timeWidth = context.batcher.getFont().getWidth(TIME.format(new Date(element.time))) + 8;
        int markerWidth = (statusIcon == null ? 0 : statusIcon.w + 3)
            + (statusSuffix.isEmpty() ? 0 : context.batcher.getFont().getWidth(statusSuffix) + 1);
        int maxWidth = this.area.ex() - timeWidth - cx - 6 - markerWidth;

        description = UIPlayerBehaviorList.crop(context.batcher.getFont(), description, maxWidth);
        context.batcher.textShadow(description, cx, textY, denied ? Colors.GRAY : hover || selected ? Colors.WHITE : 0xffcccccc);

        int tx = cx + context.batcher.getFont().getWidth(description);

        if (!statusSuffix.isEmpty())
        {
            context.batcher.textShadow(statusSuffix, tx, textY, Colors.GRAY);
            tx += context.batcher.getFont().getWidth(statusSuffix) + 1;
        }

        if (statusIcon != null)
        {
            context.batcher.icon(statusIcon, statusColor, tx, y + (s - statusIcon.h) / 2);
        }

        context.batcher.textShadow(TIME.format(new Date(element.time)), this.area.ex() - timeWidth + 4, textY, Colors.GRAY);

        if (denied)
        {
            int gradientH = Math.max(8, s / 3);

            context.batcher.gradientVBox(x, y + s - gradientH, this.area.ex(), y + s, 0x00606060, 0xAA555555);
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
                BehaviorEntry entry = this.visible().get(index);

                this.selection.set(entry, null);
                this.fireCallback();
            }
        }

        return super.subMouseClicked(context);
    }

    /** With the time sort, keep the latest entry in view, chat-log style. */
    public void scrollToBottom()
    {
        this.scroll.setScroll(Integer.MAX_VALUE);
    }

    /** Distance in pixels between the viewport bottom and the content bottom; 0 = fully scrolled. */
    public int bottomGap()
    {
        int side = this.scroll.direction.getSide(this.area);

        return Math.max(0, this.scroll.scrollSize - side - (int) this.scroll.getScroll());
    }

    public double getScrollPos()
    {
        return this.scroll.getScroll();
    }

    public void setScrollPos(double value)
    {
        this.scroll.setScroll(value);
    }
}
