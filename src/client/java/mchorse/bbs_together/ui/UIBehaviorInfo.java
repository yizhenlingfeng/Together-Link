package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.categories.FormCategory;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.BehaviorEntry;
import mchorse.bbs_together.behaviors.BehaviorLogManager;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The right column: the facts of the picked behavior — its type and time up
 * top, who did it (OP view), the subject, the model block position when there
 * is one, and then every detail item. Items are model IDs, rendered as links:
 * clicking one opens the morphing panel with that model picked in its list.
 */
public class UIBehaviorInfo extends UIElement
{
    private static final SimpleDateFormat FULL_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private BehaviorEntry entry;
    private boolean showIdentity;
    /** Clickable zones built while rendering; hit-tested on click. */
    private final List<ClickZone> zones = new ArrayList<>();

    public UIBehaviorInfo()
    {
        this.markContainer();
    }

    public void set(BehaviorEntry entry)
    {
        this.entry = entry;
    }

    public void setShowIdentity(boolean showIdentity)
    {
        this.showIdentity = showIdentity;
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        this.zones.clear();

        int x = this.area.x + 6;
        int y = this.area.y + 6;
        int width = this.area.w - 12;
        int bottom = this.area.ey() - 4;

        if (this.entry == null)
        {
            String placeholder = L10n.lang("bbs_together.behaviors.details.empty").get();

            context.batcher.textShadow(placeholder, x, y, Colors.GRAY);

            return;
        }

        BehaviorEntry entry = this.entry;

        /* Type icon, name and time */
        Icon icon = UIBehaviorList.typeIcon(entry);

        context.batcher.icon(icon, UIBehaviorList.typeColor(entry), x, y);
        context.batcher.textShadow(L10n.lang("bbs_together.behaviors.type." + entry.getType().name().toLowerCase()).get(), x + icon.w + 4, y + (icon.h - context.batcher.getFont().getHeight()) / 2, Colors.WHITE);

        y += icon.h + 6;
        y = this.line(context, x, y, width, "bbs_together.behaviors.details.time", FULL_TIME.format(new Date(entry.time)), bottom);

        if (this.showIdentity && entry.playerName != null && !entry.playerName.isEmpty())
        {
            y = this.line(context, x, y, width, "bbs_together.behaviors.details.player", entry.playerName, bottom);
        }

        /* Transferred size, when the entry carries one (downloads & uploads) */
        String bytes = entry.extra.get(BehaviorLogManager.BYTES_KEY);

        if (bytes != null)
        {
            y = this.line(context, x, y, width, "bbs_together.behaviors.details.size", formatBytes(bytes), bottom);
        }

        /* The subject line only stays for films: for models it would repeat the items */
        if (!entry.target.isEmpty() && entry.subType == BehaviorType.SUB_EDIT_FILM)
        {
            y = this.line(context, x, y, width, "bbs_together.behaviors.details.target", entry.target, bottom);
        }

        for (Map.Entry<String, String> extra : entry.extra.entrySet())
        {
            if (BehaviorLogManager.BYTES_KEY.equals(extra.getKey()))
            {
                continue;
            }

            y = this.line(context, x, y, width, extra.getKey(), extra.getValue(), bottom);
        }

        if (!entry.items.isEmpty())
        {
            String header = L10n.lang(entry.subType == BehaviorType.SUB_EDIT_FILM
                ? "bbs_together.behaviors.details.clips"
                : "bbs_together.behaviors.details.items").format(String.valueOf(entry.items.size())).get();

            if (y + 10 < bottom)
            {
                context.batcher.textShadow(header, x, y, Colors.LIGHTER_GRAY);
                y += 12;
            }

            boolean filmEntry = entry.subType == BehaviorType.SUB_EDIT_FILM;

            for (String item : entry.items)
            {
                if (y + 10 > bottom)
                {
                    context.batcher.textShadow("…", x, bottom - 10, Colors.GRAY);

                    break;
                }

                if (filmEntry)
                {
                    /* Film items are replay ids; show their labels when the film is local */
                    this.linkLine(context, x, y, width, replayDisplay(entry.target, item), entry.target, item);
                }
                else
                {
                    this.linkLine(context, x, y, width, item, null, item);
                }

                y += 10;
            }
        }
    }

    private int line(UIContext context, int x, int y, int width, String labelKey, String value, int bottom)
    {
        if (y + 12 > bottom)
        {
            return y;
        }

        String label = L10n.lang(labelKey).get() + ": ";
        int labelWidth = context.batcher.getFont().getWidth(label);
        int valueWidth = width - labelWidth;

        context.batcher.textShadow(label, x, y, Colors.GRAY);

        context.batcher.textShadow(crop(context, value, valueWidth), x + labelWidth, y, Colors.WHITE);

        return y + 12;
    }

    /** A clickable model link, lit by the theme's primary color (bright when hovered). */
    private void linkLine(UIContext context, int x, int y, int width, String value, String filmId, String modelId)
    {
        String text = crop(context, value, width);
        boolean hovered = context.mouseX >= x && context.mouseX < x + context.batcher.getFont().getWidth(text) && context.mouseY >= y && context.mouseY < y + 10;
        int color = hovered ? Colors.WHITE : BBSSettings.primaryColor();

        context.batcher.textShadow(text, x, y, color);

        ClickZone zone = new ClickZone();

        zone.x = x;
        zone.y = y;
        zone.w = context.batcher.getFont().getWidth(text);
        zone.h = 10;
        zone.filmId = filmId;
        zone.modelId = modelId;

        this.zones.add(zone);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0)
        {
            for (ClickZone zone : this.zones)
            {
                if (context.mouseX >= zone.x && context.mouseX < zone.x + zone.w && context.mouseY >= zone.y && context.mouseY < zone.y + zone.h)
                {
                    if (zone.filmId != null)
                    {
                        this.openReplay(zone.filmId, zone.modelId);
                    }
                    else
                    {
                        this.openModel(zone.modelId);
                    }

                    return true;
                }
            }
        }

        return super.subMouseClicked(context);
    }

    /** The replay's display label, looked up in the locally synced film; falls back to its id. */
    private static String replayDisplay(String filmId, String replayId)
    {
        try
        {
            mchorse.bbs_mod.film.Film film = mchorse.bbs_mod.BBSMod.getFilms().load(filmId);

            if (film != null)
            {
                mchorse.bbs_mod.film.replays.Replay replay = film.replays.getById(replayId);

                if (replay != null && !replay.label.get().isEmpty())
                {
                    return replay.label.get();
                }
            }
        }
        catch (Exception ignored)
        {}

        return replayId;
    }

    /**
     * Jump to the film panel with this film open and the replay picked in its
     * list, so a film entry's clips lead straight to their page.
     */
    private void openReplay(String filmId, String replayId)
    {
        UIServerBehaviorsPanel panel = this.getParent(UIServerBehaviorsPanel.class);

        if (panel == null || filmId == null || filmId.isEmpty() || replayId == null)
        {
            return;
        }

        UIDashboard dashboard = panel.dashboard;
        mchorse.bbs_mod.ui.film.UIFilmPanel filmPanel = null;

        for (UIDashboardPanel candidate : dashboard.getPanels().panels)
        {
            if (candidate instanceof mchorse.bbs_mod.ui.film.UIFilmPanel)
            {
                filmPanel = (mchorse.bbs_mod.ui.film.UIFilmPanel) candidate;

                break;
            }
        }

        if (filmPanel == null)
        {
            return;
        }

        try
        {
            mchorse.bbs_mod.film.Film film = mchorse.bbs_mod.BBSMod.getFilms().load(filmId);
            mchorse.bbs_mod.film.replays.Replay replay = film == null ? null : film.replays.getById(replayId);

            if (replay == null)
            {
                return;
            }

            dashboard.getPanels().setPanel(filmPanel);

            boolean needsOpen = filmPanel.getData() == null || !filmId.equals(filmPanel.getData().getId());

            if (needsOpen)
            {
                filmPanel.fill(film);
            }

            filmPanel.replayEditor.replaysList.replays.scrollToReplay(replay);
        }
        catch (Exception ignored)
        {}
    }

    /**
     * Jump to the morphing panel with this model picked in the form list, so
     * every model entry in the details has a page it leads to.
     */
    private void openModel(String modelId)
    {
        UIServerBehaviorsPanel panel = this.getParent(UIServerBehaviorsPanel.class);

        if (panel == null || modelId == null || modelId.isEmpty())
        {
            return;
        }

        UIDashboard dashboard = panel.dashboard;

        for (UIDashboardPanel candidate : dashboard.getPanels().panels)
        {
            if (candidate instanceof UIMorphingPanel)
            {
                Form form = findModelForm(modelId);

                if (form == null)
                {
                    return;
                }

                dashboard.getPanels().setPanel(candidate);
                ((UIMorphingPanel) candidate).palette.setSelected(form);

                return;
            }
        }
    }

    /** The model form of this model ID, across every form category. */
    private static Form findModelForm(String modelId)
    {
        FormCategories categories = BBSModClient.getFormCategories();

        if (categories == null)
        {
            return null;
        }

        for (FormCategory category : categories.getAllCategories())
        {
            for (Form form : category.getForms())
            {
                if (form instanceof ModelForm && ((ModelForm) form).model.get().equals(modelId))
                {
                    return form;
                }
            }
        }

        return null;
    }

    /** Human-readable byte count: B, KB, MB, GB. */
    private static String formatBytes(String raw)
    {
        long value;

        try
        {
            value = Long.parseLong(raw);
        }
        catch (NumberFormatException e)
        {
            return raw;
        }

        if (value < 1024L)
        {
            return value + " B";
        }

        double size = value;
        String[] units = {"KB", "MB", "GB", "TB"};

        for (String unit : units)
        {
            size /= 1024D;

            if (size < 1024D)
            {
                return String.format("%.1f %s", size, unit);
            }
        }

        return String.format("%.1f PB", size / 1024D);
    }

    private static String crop(UIContext context, String value, int maxWidth)
    {
        mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();

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

    /** A clickable rect of the details pane, pointing at a model or a film replay. */
    private static class ClickZone
    {
        int x;
        int y;
        int w;
        int h;
        String filmId;
        String modelId;
    }
}
