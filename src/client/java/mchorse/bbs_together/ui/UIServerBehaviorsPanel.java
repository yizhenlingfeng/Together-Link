package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.utils.UIStrip;
import mchorse.bbs_mod.ui.utils.context.UIChoiceMenu;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.BehaviorEntry;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The server behavior list panel: a search bar with sort and refresh on top,
 * then three columns — players (OP only, otherwise "no permission"), the
 * behavior log itself, and the picked behavior's details.
 *
 * <p>Data comes from the server on demand: a full snapshot when the panel
 * appears, incremental pulls every few seconds while it's on screen.</p>
 */
@Environment(value = EnvType.CLIENT)
public class UIServerBehaviorsPanel extends UIDashboardPanel
{
    public static final int BAR_HEIGHT = 20;
    private static final int MIN_SIDE = 100;
    private static final int MAX_SIDE = 400;
    private static final long QUERY_INTERVAL_MS = 3000;

    private final UISplitter leftHandle = UISplitter.pixels("bbs_together.behaviors.left", 140, MIN_SIDE, MAX_SIDE);
    private final UISplitter infoHandle = UISplitter.pixels("bbs_together.behaviors.info", 160, MIN_SIDE, MAX_SIDE);

    public UIStrip bar;
    public UITextbox search;
    public UIIcon sort;
    public UIIcon refresh;

    public UIPlayerBehaviorList players;
    public UIBehaviorList behaviors;
    public UIBehaviorInfo info;

    private final IKey noPermissionLabel = L10n.lang("bbs_together.behaviors.no_permission");

    /** Whether the left column currently holds the player list (OP) or the notice. */
    private boolean opState = false;
    private UUID playerFilter;
    private BehaviorSort sortMode = BehaviorSort.TIME;
    private long lastQueryAt;
    private long lastStamp;
    private long lastMaxId = -1;

    public UIServerBehaviorsPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.bar = new UIStrip(BAR_HEIGHT);
        this.search = new UITextbox(100, (t) -> this.behaviors.filter(t == null ? "" : t.trim()));
        this.search.placeholder(L10n.lang("bbs_together.behaviors.search"));
        this.search.delayedInput();
        this.sort = new UIIcon(Icons.LIST, (b) -> this.openSortMenu());
        this.sort.tooltip(L10n.lang("bbs_together.behaviors.sort"), Direction.BOTTOM);
        this.sort.highlight(() -> this.sortMode == BehaviorSort.BEHAVIOR, Direction.BOTTOM);
        this.refresh = new UIIcon(Icons.REFRESH, (b) -> this.fullRefresh());
        this.refresh.tooltip(L10n.lang("bbs_together.behaviors.refresh"), Direction.BOTTOM);

        this.players = new UIPlayerBehaviorList((record) ->
        {
            this.playerFilter = record == null || record.id == null ? null : record.id;
            this.rebuildBehaviors(true);
        });
        this.behaviors = new UIBehaviorList((list) -> this.info.set(list.isEmpty() ? null : list.get(0)));
        this.info = new UIBehaviorInfo();

        this.leftHandle.measure(this).onChange(this::layout)
            .range(MIN_SIDE, () -> (float) Math.min(MAX_SIDE, this.area.w - this.infoHandle.getPixels() - MIN_SIDE));
        this.infoHandle.measure(this).fromEnd().onChange(this::layout)
            .range(MIN_SIDE, () -> (float) Math.min(MAX_SIDE, this.area.w - this.leftHandle.getPixels() - MIN_SIDE));

        this.leftHandle.relative(this.players).x(1F).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);
        this.infoHandle.relative(this.info).x(0).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);

        this.bar.relative(this).xy(0, 0).w(1F).h(BAR_HEIGHT);
        this.bar.add(this.search, this.sort, this.refresh);

        this.add(this.bar, this.players, this.behaviors, this.info, this.leftHandle, this.infoHandle);

        this.applyOpState();
        this.layout();

        /* First data pull when the panel comes on screen */
        this.onAppear(() ->
        {
            this.lastQueryAt = 0;
            this.lastStamp = -1;
        });

        this.markContainer();
    }

    /** Re-place the columns after a splitter moved. */
    private void layout()
    {
        int leftWidth = this.leftHandle.getPixels();
        int infoWidth = this.infoHandle.getPixels();

        this.players.relative(this).xy(0, BAR_HEIGHT).w(leftWidth).h(1F, -BAR_HEIGHT);
        this.behaviors.relative(this).xy(leftWidth, BAR_HEIGHT).w(1F, -leftWidth - infoWidth).h(1F, -BAR_HEIGHT);
        this.info.relative(this).x(1F, -infoWidth).y(BAR_HEIGHT).w(infoWidth).h(1F, -BAR_HEIGHT);

        if (this.area.w > 0)
        {
            this.resize();
        }
    }

    /** Pick a sort from a menu, the way the texture browser does it. */
    private void openSortMenu()
    {
        UIChoiceMenu.of(BehaviorSort.values())
            .current(this.sortMode)
            .icon((s) -> s.icon)
            .label((s) -> s.label)
            .open(this.getContext(), (s) ->
            {
                if (s != this.sortMode)
                {
                    this.sortMode = s;
                    this.rebuildBehaviors(true);
                }
            });
    }

    private void fullRefresh()
    {
        TogetherClientNetwork.behaviorEntries.clear();
        TogetherClientNetwork.behaviorPlayers.clear();
        this.lastMaxId = -1;
        this.rebuildPlayers();
        this.rebuildBehaviors(true);
        this.lastQueryAt = 0;
    }

    /* Panel lifecycle */

    @Override
    public void update()
    {
        super.update();

        /* React to OP flag flips (getting opped mid-game) */
        if (this.opState != TogetherClientNetwork.isOperator)
        {
            this.applyOpState();
        }

        /* Data changed since the last pull: rebuild the lists */
        if (this.lastStamp != TogetherClientNetwork.behaviorDataStamp)
        {
            this.lastStamp = TogetherClientNetwork.behaviorDataStamp;
            this.rebuildPlayers();
            this.rebuildBehaviors(true);
        }

        long now = System.currentTimeMillis();

        if (now - this.lastQueryAt >= QUERY_INTERVAL_MS)
        {
            this.lastQueryAt = now;
            this.query(this.behaviorMaxId());
        }
    }

    private long behaviorMaxId()
    {
        return TogetherClientNetwork.behaviorEntries.isEmpty() ? 0L : TogetherClientNetwork.behaviorEntries.lastKey();
    }

    private void query(long afterId)
    {
        if (TogetherClientNetwork.isConnected())
        {
            TogetherClientNetwork.sendBehaviorQuery(afterId);
        }
    }

    /* Data plumbing */

    private void applyOpState()
    {
        this.opState = TogetherClientNetwork.isOperator;

        this.behaviors.showIdentity = this.opState;
        this.info.setShowIdentity(this.opState);

        if (!this.opState)
        {
            this.playerFilter = null;
        }

        this.rebuildPlayers();
        this.rebuildBehaviors(true);
    }

    private void rebuildPlayers()
    {
        this.players.clear();

        if (this.opState)
        {
            List<PlayerRecord> records = new ArrayList<>(TogetherClientNetwork.behaviorPlayers.values());

            records.sort(UIPlayerBehaviorList::compare);

            this.players.add(UIPlayerBehaviorList.all());
            this.players.add(records);
            this.players.update();
        }
    }

    private void rebuildBehaviors(boolean autoScroll)
    {
        List<BehaviorEntry> entries = new ArrayList<>(TogetherClientNetwork.behaviorEntries.values());

        if (this.playerFilter != null)
        {
            List<BehaviorEntry> filtered = new ArrayList<>();

            for (BehaviorEntry entry : entries)
            {
                if (this.playerFilter.equals(entry.playerId))
                {
                    filtered.add(entry);
                }
            }

            entries = filtered;
        }

        if (this.sortMode != BehaviorSort.TIME)
        {
            entries.sort(this.sortMode::compare);
        }

        this.behaviors.clear();
        this.behaviors.add(entries);
        this.behaviors.update();

        if (autoScroll && this.sortMode == BehaviorSort.TIME)
        {
            long maxId = entries.isEmpty() ? 0L : entries.get(entries.size() - 1).id;

            if (maxId != this.lastMaxId)
            {
                this.lastMaxId = maxId;
                this.behaviors.scrollToBottom();
            }
        }
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        int chrome = BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.y + BAR_HEIGHT, chrome);
        context.batcher.box(this.players.area.x, this.players.area.y, this.players.area.ex(), this.players.area.ey(), chrome);

        super.render(context);

        /* "No permission" over the left column, for non-OPs */
        if (!this.opState)
        {
            String label = this.noPermissionLabel.get();
            mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer font = context.batcher.getFont();
            int textX = this.players.area.x + (this.players.area.w - font.getWidth(label)) / 2;
            int textY = this.players.area.y + (this.players.area.h - font.getHeight()) / 2;

            context.batcher.textShadow(label, textX, textY, Colors.GRAY);
        }
    }
}
