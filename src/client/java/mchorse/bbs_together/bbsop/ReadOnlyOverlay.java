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
                drawVeil(context, this.area);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                /* Swallow every click so the widgets underneath never receive it — but ONLY
                 * when the cursor is actually over the veil: BBS walks every child without
                 * an area pre-check, so an unconditional "true" here would eat clicks meant
                 * for unrelated elements (it broke the film editor's timeline scrubbing). */
                return this.area.isInside(context);
            }

            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                /* Scrolling a trackpad/spinbox edits values — swallow it too. */
                return this.area.isInside(context);
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
                drawVeil(context, this.area);
            }

            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return context.mouseButton == 1 && this.area.isInside(context);
            }

            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                return false;
            }
        };
    }

    /** Attaches (or removes) the overlay on {@code host} depending on {@code readOnly}. */
    public static void ensureAttached(UIElement host, UIElement[] slot, boolean readOnly, boolean clickThrough)
    {
        if (readOnly)
        {
            if (slot[0] == null)
            {
                slot[0] = clickThrough ? createClickThrough() : create();
                slot[0].relative(host).x(0).y(0).w(1F).h(1F);
                host.add(slot[0]);
            }
        }
        else if (slot[0] != null)
        {
            host.remove(slot[0]);
            slot[0] = null;
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
