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
import java.util.Set;

/**
 * The right column: the facts of the picked behavior — its type and time up
 * top, who did it (OP view), the subject, the model block position when there
 * is one, and then every detail item. Items are model IDs, rendered as links:
 * clicking one opens the morphing panel with that model picked in its list.
 */
public class UIBehaviorInfo extends UIElement
{
    private static final SimpleDateFormat FULL_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat LINE_TIME = new SimpleDateFormat("MM-dd HH:mm:ss");
    /** Internal extra keys of model-delete requests, shown through dedicated lines instead. */
    private static final Set<String> REQUEST_EXTRA_KEYS = Set.of("status", "by", "byId", "decidedAt");

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

        /* A model-delete request shows its approval state (and who decided it) in
         * friendly words instead of the raw extra keys below. */
        if (UIBehaviorList.isModelDeleteRequest(entry))
        {
            String status = entry.extra.getOrDefault("status", "pending");
            String statusKey = "approved".equals(status) ? "approved" : "denied".equals(status) ? "denied" : "pending";

            y = this.line(context, x, y, width, "bbs_together.behaviors.details.status", L10n.lang("bbs_together.behaviors.status." + statusKey).get(), bottom);

            String by = entry.extra.get("by");

            if (by != null && !by.isEmpty())
            {
                y = this.line(context, x, y, width, "bbs_together.behaviors.details.approver", by, bottom);
            }
        }

        for (Map.Entry<String, String> extra : entry.extra.entrySet())
        {
            if (BehaviorLogManager.BYTES_KEY.equals(extra.getKey())
                || REQUEST_EXTRA_KEYS.contains(extra.getKey()))
            {
                continue;
            }

            y = this.line(context, x, y, width, extra.getKey(), extra.getValue(), bottom);
        }

        /* The per-repeat timeline of a merged entry: one row per timestamp,
         * each with the items that came with that repeat. */
        if (entry.occurrences.size() > 1)
        {
            y = this.renderTimeline(context, entry, x, y, width, bottom);
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
                    this.linkLine(context, x, y, width, item, entry.target, item, null);
                }
                else
                {
                    this.linkLine(context, x, y, width, item, null, null, item);
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

    /**
     * One row per repeat of a merged entry, oldest first: the timestamp in
     * gray, then that repeat's items as clickable links (film clips or models,
     * same as the pooled items above).
     */
    private int renderTimeline(UIContext context, BehaviorEntry entry, int x, int y, int width, int bottom)
    {
        String header = L10n.lang("bbs_together.behaviors.details.timeline").format(String.valueOf(entry.occurrences.size())).get();

        if (y + 12 > bottom)
        {
            return y;
        }

        context.batcher.textShadow(header, x, y, Colors.LIGHTER_GRAY);

        y += 12;

        boolean filmEntry = entry.subType == BehaviorType.SUB_EDIT_FILM;

        for (BehaviorEntry.Occurrence occurrence : entry.occurrences)
        {
            if (y + 10 > bottom)
            {
                context.batcher.textShadow("…", x, bottom - 10, Colors.GRAY);

                break;
            }

            String time = LINE_TIME.format(new Date(occurrence.time));

            context.batcher.textShadow(time, x, y, Colors.GRAY);

            int lx = x + context.batcher.getFont().getWidth(time) + 6;

            for (String item : occurrence.items)
            {
                int itemWidth = x + width - lx;

                if (itemWidth < 20)
                {
                    break;
                }

                if (filmEntry)
                {
                    this.linkLine(context, lx, y, itemWidth, item, entry.target, item, null);
                }
                else
                {
                    this.linkLine(context, lx, y, itemWidth, item, null, null, item);
                }

                lx += context.batcher.getFont().getWidth(crop(context, item, itemWidth)) + 6;
            }

            y += 10;
        }

        return y + 2;
    }

    /** A clickable model link, lit by the theme's primary color (bright when hovered). */
    private void linkLine(UIContext context, int x, int y, int width, String value, String filmId, String clip, String modelId)
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
        zone.clip = clip;
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
                        this.openClip(zone.filmId, zone.clip);
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

    /**
     * Jump to the film panel with this film open and the picked camera clip
     * selected on its timeline, so a film entry's clips lead straight to
     * their page. {@code label} is the clip's title, or {@code #index} for
     * an untitled clip.
     */
    private void openClip(String filmId, String label)
    {
        UIServerBehaviorsPanel panel = this.getParent(UIServerBehaviorsPanel.class);

        if (panel == null || filmId == null || filmId.isEmpty() || label == null || label.isEmpty())
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

            if (film == null)
            {
                return;
            }

            mchorse.bbs_mod.utils.clips.Clip target = null;

            if (label.startsWith("#"))
            {
                int index = Integer.parseInt(label.substring(1));

                if (index >= 0 && index < film.camera.get().size())
                {
                    target = film.camera.get().get(index);
                }
            }
            else
            {
                for (mchorse.bbs_mod.utils.clips.Clip clip : film.camera.get())
                {
                    if (label.equals(clip.title.get()))
                    {
                        target = clip;

                        break;
                    }
                }
            }

            if (target == null)
            {
                return;
            }

            dashboard.getPanels().setPanel(filmPanel);

            boolean needsOpen = filmPanel.getData() == null || !filmId.equals(filmPanel.getData().getId());

            if (needsOpen)
            {
                filmPanel.fill(film);
            }

            filmPanel.cameraEditor.clips.pickClip(target);
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

    /** A clickable rect of the details pane, pointing at a model or a film camera clip. */
    private static class ClickZone
    {
        int x;
        int y;
        int w;
        int h;
        /** Film id for camera clip zones. */
        String filmId;
        /** Clip title, or {@code #index} for an untitled clip. */
        String clip;
        String modelId;
    }
}
