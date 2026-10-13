package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.context.ContextAction;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.network.TogetherClientNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/**
 * The "change editable domains" menu with one extra trick: the record sub-permission
 * ({@link BbsOpDomain#RECORD}) is not a top-level row — it pops out as a small button
 * to the right of the film row while that row is hovered, visually nested under film.
 *
 * <p>BBS's context menu framework has no submenu-on-hover primitive, so this is drawn
 * by hand. The film row ({@link FilmDomainAction}) reports its screen position and hover
 * state while rows render (inside the list's scissor, so the popup itself cannot be drawn
 * there); this menu then paints the popup after {@code super.render()}, outside any
 * clipping, and consumes clicks on it before the base class gets a chance to interpret
 * the click as "outside the menu" and tear the whole menu down.</p>
 */
@Environment(value = EnvType.CLIENT)
public class UIDomainMenuWithRecord extends UISimpleContextMenu
{
    /** How many frames the popup lingers after the mouse left the film row — enough
     * to travel the few pixels of gap onto the popup without it blinking away. */
    private static final int GRACE_FRAMES = 8;
    /** Horizontal gap between the menu's right edge and the popup. */
    private static final int GAP = 4;
    private static final int MIN_WIDTH = 90;

    private final UUID targetId;
    /** Last known record-allowed state, used when the player vanished from the snapshot. */
    private boolean lastAllowed;

    /* Film row geometry, refreshed every frame by {@link FilmDomainAction#render}. */
    private int filmRowRight;
    private int filmRowY;
    private int filmRowH;
    private boolean filmHovered;

    /* Popup geometry from the last frame, reused for hit testing between renders. */
    private boolean popupShown;
    private int popupX;
    private int popupY;
    private int popupW;
    private int popupH;
    private int grace;

    public UIDomainMenuWithRecord(PlayerRecord record)
    {
        super();

        this.targetId = record.id;
        this.lastAllowed = UIPlayerBehaviorList.isDomainAllowed(record, BbsOpDomain.RECORD);
    }

    /** The film row, wrapped so it reports where it is and whether it is hovered. */
    public ContextAction filmAction(Icon icon, IKey label, Runnable runnable)
    {
        return new FilmDomainAction(icon, label, runnable);
    }

    private static boolean isRecordAllowed(PlayerRecord record)
    {
        return record == null || UIPlayerBehaviorList.isDomainAllowed(record, BbsOpDomain.RECORD);
    }

    private boolean isRecordAllowedNow()
    {
        PlayerRecord latest = TogetherClientNetwork.behaviorPlayers.get(this.targetId);

        if (latest != null)
        {
            this.lastAllowed = isRecordAllowed(latest);
        }

        return this.lastAllowed;
    }

    private void toggleRecord()
    {
        PlayerRecord latest = TogetherClientNetwork.behaviorPlayers.get(this.targetId);
        boolean nowAllowed = latest == null ? this.lastAllowed : isRecordAllowed(latest);

        TogetherClientNetwork.sendSetBbsGrant(this.targetId, BbsOpDomain.RECORD.id, !nowAllowed);
    }

    /* Popup lifecycle */

    private void updatePopup(UIContext context)
    {
        if (this.filmHovered)
        {
            this.popupShown = true;
            this.grace = GRACE_FRAMES;
        }
        else if (this.popupShown)
        {
            if (this.isMouseInsidePopup(context))
            {
                this.grace = GRACE_FRAMES;
            }
            else if (--this.grace < 0)
            {
                this.popupShown = false;
            }
        }

        if (!this.popupShown)
        {
            return;
        }

        FontRenderer font = context.batcher.getFont();
        String label = this.popupLabel();
        int w = Math.max(MIN_WIDTH, 28 + font.getWidth(label));
        int h = Math.max(this.filmRowH, 16);
        int x = this.filmRowRight + GAP;
        int screenW = this.getContext().menu.width;

        if (x + w > screenW - 2)
        {
            x = screenW - w - 2;
        }

        this.popupX = x;
        this.popupY = this.filmRowY;
        this.popupW = w;
        this.popupH = h;
    }

    private String popupLabel()
    {
        boolean allowed = this.isRecordAllowedNow();

        return L10n.lang("bbs_together.bbsop.context.domain").get()
            .replace("{domain}", L10n.lang("bbs_together.bbsop.domain.record").get())
            .replace("{state}", L10n.lang(allowed ? "bbs_together.bbsop.context.allowed" : "bbs_together.bbsop.context.denied").get());
    }

    private Icon popupIcon()
    {
        return this.lastAllowed ? Icons.ADD : Icons.CLOSE;
    }

    private boolean isMouseInsidePopup(UIContext context)
    {
        int x = context.mouseX();
        int y = context.mouseY();

        return x >= this.popupX && x < this.popupX + this.popupW
            && y >= this.popupY && y < this.popupY + this.popupH;
    }

    private void renderPopup(UIContext context)
    {
        boolean hover = this.isMouseInsidePopup(context);
        int x = this.popupX;
        int y = this.popupY;

        context.batcher.dropShadow(x, y, x + this.popupW, y + this.popupH, 10, BBSSettings.panelShadowOpaqueColor(), BBSSettings.panelShadowTransparentColor());
        context.batcher.box(x, y, x + this.popupW, y + this.popupH, BBSSettings.raisedSurface());

        if (hover)
        {
            RowStyle.hover(context.batcher, x, y, this.popupW, this.popupH, 0);
        }

        context.batcher.icon(this.popupIcon(), x + 2, y + this.popupH / 2, 0, 0.5F);
        context.batcher.text(this.popupLabel(), x + 22, y + (this.popupH - context.batcher.getFont().getHeight()) / 2 + 1, Colors.WHITE, false);
    }

    /* Framework hooks */

    @Override
    public void render(UIContext context)
    {
        this.filmHovered = false;

        super.render(context);

        this.updatePopup(context);

        if (this.popupShown)
        {
            this.renderPopup(context);
        }
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        /* Consume clicks on the popup before the base class sees them — the popup sits
         * outside this menu's area, so a click there reads as "outside" and would tear
         * the menu down instead of toggling the permission. */
        if (this.popupShown && context.mouseButton == 0 && this.isMouseInsidePopup(context))
        {
            this.toggleRecord();
            this.dismiss();

            return true;
        }

        return super.subMouseClicked(context);
    }

    /**
     * The film domain row: renders exactly like a plain action, but lets the menu know
     * where the row sits on screen and whether the cursor is on it.
     */
    private class FilmDomainAction extends ContextAction
    {
        public FilmDomainAction(Icon icon, IKey label, Runnable runnable)
        {
            super(icon, label, runnable);
        }

        @Override
        public void render(UIContext context, FontRenderer font, int x, int y, int w, int h, boolean hover, boolean selected)
        {
            super.render(context, font, x, y, w, h, hover, selected);

            filmRowRight = x + w;
            filmRowY = y;
            filmRowH = h;

            if (hover)
            {
                filmHovered = true;
            }
        }
    }
}
