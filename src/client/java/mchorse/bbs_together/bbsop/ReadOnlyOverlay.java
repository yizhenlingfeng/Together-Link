package mchorse.bbs_together.bbsop;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Read-only overlay elements: a translucent grey wash with a centred padlock and the
 * localized "view only" caption, mirroring what {@code UIElement.renderLockedArea} draws
 * for disabled inputs. Every overlay reads the permission at draw/click time, so once
 * attached it stays on its host for life and simply goes inert on a permission change.
 */
@Environment(EnvType.CLIENT)
public final class ReadOnlyOverlay
{
    private ReadOnlyOverlay() {}

    /** Builds a veil element sized via {@code relative(...)}, swallowing clicks and scrolls. */
    public static UIElement create()
    {
        return new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                if (BbsOpClientState.isReadOnly())
                {
                    drawVeil(context, this.area);
                }
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                /* Only when read-only AND the cursor is over the veil: BBS walks every child
                 * without an area pre-check, so an unconditional "true" would eat clicks meant
                 * for unrelated elements (it broke the timeline scrubbing once already). */
                return BbsOpClientState.isReadOnly() && this.area.isInside(context);
            }

            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                /* Scrolling a trackpad/spinbox edits values — swallow it too. */
                return BbsOpClientState.isReadOnly() && this.area.isInside(context);
            }
        };
    }

    /** Variant for surfaces that stay interactive while looking locked: only right-clicks
     * (context menus) are swallowed, left-click selection and scrolling pass through. */
    public static UIElement createClickThrough()
    {
        return new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                if (BbsOpClientState.isReadOnly())
                {
                    drawVeil(context, this.area);
                }
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return BbsOpClientState.isReadOnly() && context.mouseButton == 1 && this.area.isInside(context);
            }

            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                return false;
            }
        };
    }

    /** Attaches the overlay on {@code host} once, sized to fill it. */
    public static void ensureAttached(UIElement host, UIElement[] slot, boolean clickThrough)
    {
        if (slot[0] == null)
        {
            slot[0] = clickThrough ? createClickThrough() : create();
            slot[0].relative(host).x(0).y(0).w(1F).h(1F);
            host.add(slot[0]);
        }
    }

    /** Attaches one shield per {@code boundTo} element, in parallel with {@code slots}. */
    public static void ensureShieldsAttached(UIElement parent, UIElement[] slots, UIElement... boundTo)
    {
        for (int i = 0; i < boundTo.length; i++)
        {
            ensureShieldAttached(parent, slots, i, boundTo[i]);
        }
    }

    /**
     * Attaches an invisible click/scroll shield whose area is locked to {@code boundTo}'s
     * live area. Needed for hosts laid out by flow containers (columns/splitters), where a
     * plain 100%-sized child would just become another stacked row instead of covering the
     * widgets.
     */
    public static void ensureShieldAttached(UIElement parent, UIElement[] slots, int slot, UIElement boundTo)
    {
        if (slots[slot] == null)
        {
            slots[slot] = new UIElement()
            {
                @Override
                public void render(UIContext context)
                {
                    this.area.copy(boundTo.area);
                }

                @Override
                protected boolean subMouseClicked(UIContext context)
                {
                    return BbsOpClientState.isReadOnly() && this.syncArea().isInside(context);
                }

                @Override
                protected boolean subMouseScrolled(UIContext context)
                {
                    return BbsOpClientState.isReadOnly() && this.syncArea().isInside(context);
                }

                /* The click path runs before (or instead of) render: sync here too, so the hit
                 * test never reads a stale or never-initialized (0x0) area. */
                private Area syncArea()
                {
                    this.area.copy(boundTo.area);

                    return this.area;
                }
            };

            /* BBS culls zero-area children before rendering them — without this the shield
             * starts at 0x0, render never runs and the area never gets initialized. */
            slots[slot].noCulling();
            parent.add(slots[slot]);
        }
    }

    public static String label()
    {
        return L10n.lang("bbs_together.bbsop.view_only").get();
    }

    /** Grey veil + centred padlock + "view only" caption. */
    public static void drawVeil(UIContext context, Area a)
    {
        if (a.w <= 0 || a.h <= 0)
        {
            return;
        }

        a.render(context.batcher, Colors.A50);

        String label = label();

        context.batcher.outlinedIcon(Icons.LOCKED, a.mx(), a.my() - 6, 0.5F, 0.5F);
        context.batcher.text(label, a.mx() - context.batcher.getFont().getWidth(label) / 2F, a.my() + 6, Colors.WHITE);
    }
}
