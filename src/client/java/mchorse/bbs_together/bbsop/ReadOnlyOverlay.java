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
 * The BBSFS-native look for a read-only surface: a translucent grey wash over the area,
 * a centred padlock and the localized "view only" caption — mirroring what
 * {@code UIElement.renderLockedArea} draws for individual disabled input fields.
 *
 * <p>Overlays are permission-aware at draw/click time: once attached they stay on their host
 * for its whole lifetime and simply go inert when the player is no longer read-only, so a
 * permission change takes effect on the very next frame without any extra syncing.</p>
 */
@Environment(EnvType.CLIENT)
public final class ReadOnlyOverlay
{
    private ReadOnlyOverlay() {}

    /** Builds a click-consuming overlay element that must be laid out via {@code relative(...)}. */
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
                /* Swallow every click so the widgets underneath never receive it — but ONLY
                 * when read-only and the cursor is actually over the veil: BBS walks every
                 * child without an area pre-check, so an unconditional "true" here would eat
                 * clicks meant for unrelated elements (it broke the timeline scrubbing). */
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

    /**
     * Variant for surfaces that must stay interactive while looking locked (the replay list:
     * left-click selection and scrolling pass through, only right-click context menus are
     * swallowed).
     */
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

    /** Attaches the overlay on {@code host} once; it then tracks the permission by itself. */
    public static void ensureAttached(UIElement host, UIElement[] slot, boolean clickThrough)
    {
        if (slot[0] == null)
        {
            slot[0] = clickThrough ? createClickThrough() : create();
            slot[0].relative(host).x(0).y(0).w(1F).h(1F);
            host.add(slot[0]);
        }
    }

    /**
     * Attaches an invisible click/scroll shield that keeps its own area locked to
     * {@code boundTo}'s area — synced every frame in {@code render}, so it follows window
     * resizes and layout changes immediately. Needed for hosts laid out by flow containers
     * (columns/splitters), where a plain 100%-sized child would just become another stacked
     * row instead of covering the widgets.
     */
    public static void ensureShieldAttached(UIElement parent, UIElement[] slot, UIElement boundTo)
    {
        if (slot[0] == null)
        {
            slot[0] = new UIElement()
            {
                @Override
                public void render(UIContext context)
                {
                    this.area.copy(boundTo.area);
                }

                @Override
                protected boolean subMouseClicked(UIContext context)
                {
                    return BbsOpClientState.isReadOnly() && this.area.isInside(context);
                }

                @Override
                protected boolean subMouseScrolled(UIContext context)
                {
                    return BbsOpClientState.isReadOnly() && this.area.isInside(context);
                }
            };

            parent.add(slot[0]);
        }
    }

    public static String label()
    {
        return L10n.lang("bbs_together.bbsop.view_only").get();
    }

    /** The BBSFS-native lock look: grey veil + centred padlock + "view only" caption. */
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
