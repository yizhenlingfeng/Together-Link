package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.behaviors.BehaviorEntry;

/**
 * How the behavior list orders its rows.
 *
 * <p>{@link #TIME} keeps the log chat-style: oldest on top, newest at the
 * bottom, auto-scrolled down. {@link #BEHAVIOR} groups by type, D -> E -> U
 * (the English initials of the three behaviors), oldest first within a group.</p>
 */
public enum BehaviorSort
{
    TIME(Icons.TIME, L10n.lang("bbs_together.behaviors.sort.time"))
    {
        @Override
        public int compare(BehaviorEntry a, BehaviorEntry b)
        {
            return Long.compare(a.id, b.id);
        }
    },
    BEHAVIOR(Icons.LIST, L10n.lang("bbs_together.behaviors.sort.behavior"))
    {
        @Override
        public int compare(BehaviorEntry a, BehaviorEntry b)
        {
            int key = Integer.compare(sortKey(a), sortKey(b));

            return key != 0 ? key : Long.compare(a.id, b.id);
        }
    };

    /** D -> E -> U grouping key, matching the plan's behavior sort. */
    public static int sortKey(BehaviorEntry entry)
    {
        switch (entry.getType())
        {
            case DOWNLOAD: return 0;
            case EDIT: return 1;
            case UPLOAD: return 2;
            default: return 3;
        }
    }

    public final Icon icon;
    public final IKey label;

    BehaviorSort(Icon icon, IKey label)
    {
        this.icon = icon;
        this.label = label;
    }

    /** Row order within the list, oldest first inside a group. */
    public abstract int compare(BehaviorEntry a, BehaviorEntry b);
}
