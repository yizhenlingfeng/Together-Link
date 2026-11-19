package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeSelection;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client side of the multi-keyframe lock. It only touches the foreign map of the
 * nearest replay or camera clip; the server re-checks every write through
 * {@link KeyframeLockGuard}.
 */
@Environment(EnvType.CLIENT)
public final class KeyframeLockClient
{
    /** Problem reasons reported by {@link #selection(UIKeyframes)}. */
    public static final String PROBLEM_DURATION = "selection_duration";
    public static final String PROBLEM_RANGE = "selection_range";
    public static final String PROBLEM_OVERLAP = "selection_overlap";

    private KeyframeLockClient()
    {}

    /* The keyframe editor draws its lock mask from the sheets alone, without handing the
     * view over, so the film it belongs to is parked here for the duration of a render. */
    private static Film rendering;

    public static void renderFilm(Film film)
    {
        rendering = film;
    }

    public static boolean canManage()
    {
        return ClipLockClient.canManage();
    }

    /** Whether the clip that owns this keyframe sheet keeps the local player out. */
    public static boolean lockedForSelf(UIKeyframeSheet sheet)
    {
        Film film = rendering;

        if (sheet == null || film == null)
        {
            return false;
        }

        Clip clip = null;

        for (Clip candidate : film.camera.get())
        {
            if (candidate == KeyframeLockData.ownerOf(sheet.channel))
            {
                clip = candidate;
                break;
            }
        }

        return clip != null && !ClipLockClient.canEdit(clip);
    }

    /**
     * The ranges the current selection would lock, plus what the menu needs to explain itself:
     * the keys that bound each range, and the lock that already covers part of it.
     */
    public record Selection(ValueGroup owner, List<KeyframeLockData.Range> ranges, List<Keyframe<?>> keys, String problem, String overlapping)
    {
        public static Selection fail(String problem)
        {
            return new Selection(null, List.of(), List.of(), problem, null);
        }

        public boolean ok()
        {
            return this.problem == null && this.owner != null && !this.ranges.isEmpty();
        }
    }

    public static Film film(UIKeyframes view)
    {
        if (view instanceof UIFilmKeyframes filmKeyframes && filmKeyframes.editor != null)
        {
            return filmKeyframes.editor.getFilm();
        }

        return null;
    }

    /**
     * Turns the current multi-sheet selection into lock ranges. Every key between
     * the first and the last selected key must be selected on its channel; a loop
     * block that intersects the selection expands the range to the whole block.
     */
    public static Selection selection(UIKeyframes view)
    {
        if (view == null || view.getGraph() == null)
        {
            return Selection.fail(PROBLEM_DURATION);
        }

        Map<String, float[]> bounds = new LinkedHashMap<>();
        Map<String, KeyframeChannel<?>> channels = new LinkedHashMap<>();
        List<Keyframe<?>> keys = new ArrayList<>();
        ValueGroup owner = null;
        boolean anySelection = false;

        for (UIKeyframeSheet sheet : view.getGraph().getSheets())
        {
            KeyframeSelection selection = sheet.selection;

            if (selection == null || !selection.hasAny())
            {
                continue;
            }

            anySelection = true;

            ValueGroup sheetOwner = KeyframeLockData.ownerOf(sheet.channel);

            if (sheetOwner == null || (owner != null && owner != sheetOwner))
            {
                return Selection.fail(PROBLEM_RANGE);
            }

            owner = sheetOwner;

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            Keyframe<?> first = null;
            Keyframe<?> last = null;

            for (int index : selection.getIndices())
            {
                if (index < 0 || index >= sheet.channel.getKeyframes().size())
                {
                    continue;
                }

                Keyframe<?> key = (Keyframe<?>) sheet.channel.getKeyframes().get(index);

                min = Math.min(min, key.getTick());
                max = Math.max(max, key.getTick());
                first = first == null || key.getTick() < first.getTick() ? key : first;
                last = last == null || key.getTick() > last.getTick() ? key : last;
            }

            if (min > max)
            {
                continue;
            }

            KeyframeChannel<?> channel = sheet.channel;

            for (Object entry : channel.getLoops())
            {
                KeyframeLoop loop = (KeyframeLoop) entry;
                float end = channel.getLoopEnd(loop);

                if (loop.start() <= max && end >= min)
                {
                    min = Math.min(min, loop.start());
                    max = Math.max(max, end);
                }
            }

            for (int i = 0; i < channel.getKeyframes().size(); i++)
            {
                float tick = tick(channel, i);

                if (tick >= min && tick <= max && !selection.has(i))
                {
                    return Selection.fail(PROBLEM_RANGE);
                }
            }

            if (first != null && !keys.contains(first))
            {
                keys.add(first);
            }

            if (last != null && !keys.contains(last))
            {
                keys.add(last);
            }

            String id = channel.getId();
            float[] current = bounds.get(id);

            if (current == null)
            {
                bounds.put(id, new float[] {min, max});
                channels.put(id, channel);
            }
            else
            {
                current[0] = Math.min(current[0], min);
                current[1] = Math.max(current[1], max);
            }
        }

        if (!anySelection || bounds.isEmpty())
        {
            return Selection.fail(PROBLEM_DURATION);
        }

        ArrayList<KeyframeLockData.Range> ranges = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : bounds.entrySet())
        {
            float start = entry.getValue()[0];
            float end = entry.getValue()[1];

            if (end <= start)
            {
                return Selection.fail(PROBLEM_DURATION);
            }

            ranges.add(new KeyframeLockData.Range(entry.getKey(), start, end));
        }

        for (KeyframeLockData lock : KeyframeLockData.get(owner))
        {
            for (KeyframeLockData.Range range : lock.ranges())
            {
                for (KeyframeLockData.Range candidate : ranges)
                {
                    if (range.channel().equals(candidate.channel()) && range.intersects(candidate.start(), candidate.end()))
                    {
                        return new Selection(owner, ranges, keys, PROBLEM_OVERLAP, lock.id());
                    }
                }
            }
        }

        return new Selection(owner, ranges, keys, null, null);
    }

    public static boolean create(UIKeyframes view, Selection selection)
    {
        Film film = film(view);

        if (film == null || selection == null || !selection.ok() || !canManage())
        {
            return false;
        }

        ArrayList<KeyframeLockData> locks = new ArrayList<>(KeyframeLockData.get(selection.owner()));

        locks.add(new KeyframeLockData(null, selection.ranges()));
        KeyframeLockData.set(selection.owner(), locks);
        broadcast(film, selection.owner());

        /* Locking a range takes the keys off the selection: dragging what is now frozen would
         * only look broken. The first locked key becomes the picked one instead, the way the
         * keyframe editor moves the pick on every other click. */
        Keyframe<?> first = selection.keys().isEmpty() ? null : selection.keys().get(0);

        if (first != null)
        {
            view.getGraph().clearSelection();
            view.pickKeyframe(first);
        }

        return true;
    }

    public static boolean unlock(UIKeyframes view, ValueGroup owner, String id)
    {
        Film film = film(view);

        if (film == null || owner == null || id == null || !canManage())
        {
            return false;
        }

        ArrayList<KeyframeLockData> locks = new ArrayList<>();

        for (KeyframeLockData lock : KeyframeLockData.get(owner))
        {
            if (!lock.id().equals(id))
            {
                locks.add(lock);
            }
        }

        KeyframeLockData.set(owner, locks);
        broadcast(film, owner);

        return true;
    }

    /* Selection filtering */

    private static float tick(KeyframeChannel<?> channel, int index)
    {
        return ((Keyframe<?>) channel.getKeyframes().get(index)).getTick();
    }

    /** The channel an undo entry edited, either the value itself or the group a key sits in. */
    private static KeyframeChannel<?> channelOf(BaseValue value)
    {
        if (value instanceof KeyframeChannel<?> channel)
        {
            return channel;
        }

        BaseValue parent = value == null ? null : value.getParent();

        return parent instanceof KeyframeChannel<?> channel ? channel : null;
    }

    public static void filterLockedSelection(UIKeyframes view)
    {
        for (UIKeyframeSheet sheet : view.getGraph().getSheets())
        {
            List<KeyframeLockData.Range> ranges = rangesForSheet(sheet);

            if (ranges.isEmpty() || sheet.selection == null || !sheet.selection.hasAny())
            {
                continue;
            }

            ArrayList<Integer> remove = new ArrayList<>();

            for (int index : sheet.selection.getIndices())
            {
                if (index >= 0 && index < sheet.channel.getKeyframes().size() && locked(ranges, tick(sheet.channel, index)))
                {
                    remove.add(index);
                }
            }

            for (int index : remove)
            {
                sheet.selection.remove(index);
            }
        }
    }

    private static List<KeyframeLockData.Range> rangesForSheet(UIKeyframeSheet sheet)
    {
        ValueGroup owner = KeyframeLockData.ownerOf(sheet.channel);

        return owner == null ? List.of() : KeyframeLockData.rangesFor(owner, sheet.channel.getId());
    }

    /* Hit testing */

    public record HitRecord(ValueGroup owner, KeyframeLockData lock, KeyframeLockData.Range range, int y, int height)
    {}

    /**
     * Pixel {@code [y, height]} of a sheet row.
     *
     * <p>The dope sheet stacks every channel as its own row. The single-channel curve graph shows
     * one channel, so its lane is the whole plot below the tick ruler: the height covers the row
     * and the y is clamped into the graph area by the caller.</p>
     */
    public static int[] row(UIKeyframes view, UIKeyframeSheet sheet)
    {
        if (view.getGraph() == view.getDopeSheet())
        {
            return new int[] {view.getDopeSheet().getDopeSheetY(sheet), view.getDopeSheet().getTrackHeight(sheet)};
        }

        return new int[] {view.graphArea.y + IUIKeyframeGraph.TOP_MARGIN, Math.max(16, view.graphArea.ey() - view.graphArea.y - IUIKeyframeGraph.TOP_MARGIN)};
    }

    /** Whether a loop block of a sheet reacts to the mouse at {@code mouseX}, using BBS' own grab margins. */
    public static boolean loopLocked(UIKeyframes view, UIKeyframeSheet sheet, int mouseX)
    {
        ValueGroup owner = KeyframeLockData.ownerOf(sheet.channel);

        if (owner == null)
        {
            return false;
        }

        for (Object entry : sheet.channel.getLoops())
        {
            KeyframeLoop loop = (KeyframeLoop) entry;
            float end = sheet.channel.getLoopEnd(loop);
            int x1 = view.toGraphX(loop.start());
            int x2 = view.toGraphX(end);

            if (mouseX >= x1 - 3 && mouseX <= x2 + 4 && KeyframeLockData.loopLocked(owner, sheet.channel.getId(), loop, end))
            {
                return true;
            }
        }

        return false;
    }

    /** Lock ranges drawn under the mouse, one entry per range. */
    public static List<HitRecord> hits(UIKeyframes view, int mouseX, int mouseY)
    {
        ArrayList<HitRecord> hits = new ArrayList<>();
        IUIKeyframeGraph graph = view.getGraph();

        if (graph == null || !view.graphArea.isInside(mouseX, mouseY) || mouseY < view.graphArea.y + IUIKeyframeGraph.TOP_MARGIN)
        {
            return hits;
        }

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            ValueGroup owner = KeyframeLockData.ownerOf(sheet.channel);
            List<KeyframeLockData> locks = owner == null ? List.of() : KeyframeLockData.get(owner);

            if (locks.isEmpty())
            {
                continue;
            }

            int[] row = row(view, sheet);

            if (mouseY < row[0] || mouseY >= row[0] + row[1])
            {
                continue;
            }

            for (KeyframeLockData lock : locks)
            {
                for (KeyframeLockData.Range range : lock.ranges())
                {
                    if (!range.channel().equals(sheet.channel.getId()))
                    {
                        continue;
                    }

                    int x1 = view.toGraphX(range.start());
                    int x2 = view.toGraphX(range.end());

                    if (mouseX >= x1 - 3 && mouseX <= x2 + 3)
                    {
                        hits.add(new HitRecord(owner, lock, range, row[0], row[1]));
                    }
                }
            }
        }

        return hits;
    }

    /* Storage */

    private static void broadcast(Film film, ValueGroup owner)
    {
        if (film == null || owner == null || !TogetherClientNetwork.isConnected())
        {
            return;
        }

        List<String> path;

        if (owner instanceof Replay replay)
        {
            path = List.of("replays", replay.getId());
        }
        else if (owner instanceof Clip clip && film.camera != null)
        {
            int index = film.camera.getIndex(clip);

            if (index < 0)
            {
                return;
            }

            path = List.of("camera", String.valueOf(index));
        }
        else
        {
            return;
        }

        MapType payload = new MapType();
        Map<String, BaseType> foreign = KeyframeLockData.foreignOrNull(owner);

        if (foreign == null)
        {
            return;
        }

        for (Map.Entry<String, BaseType> entry : foreign.entrySet())
        {
            payload.put(entry.getKey(), entry.getValue().copy());
        }

        TogetherClientNetwork.sendSyncDataPayload(film.getId(), path, payload);
    }

    /* Undo */

    /**
     * Whether a remembered value, written back by Ctrl+Z or Ctrl+Y, would rewrite keyframes or
     * loop blocks that a lock covers. {@code target} is the value the undo entry was recorded
     * for: a whole channel or a single keyframe. A channel payload is compared key by key and
     * loop by loop, so a rollback that leaves locked keys alone still goes through; a value that
     * cannot be inspected that way is refused when the owner has any lock at all.
     */
    public static boolean undoBlocked(BaseValue target, BaseType value)
    {
        if (target == null || value == null)
        {
            return false;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(target);

        if (owner == null)
        {
            return false;
        }

        KeyframeChannel<?> channel = channelOf(target);
        String channelId = channel != null ? channel.getId() : value.isMap() ? value.asMap().getString("type") : null;
        List<KeyframeLockData.Range> ranges = channelId == null ? List.of() : KeyframeLockData.rangesFor(owner, channelId);

        if (ranges.isEmpty())
        {
            return false;
        }

        if (target instanceof Keyframe<?> keyframe)
        {
            return locked(ranges, keyframe.getTick());
        }

        if (!value.isMap() || !value.asMap().has("keyframes"))
        {
            return !value.isList() && KeyframeLockData.hasAny(owner);
        }

        MapType values = value.asMap();
        ListType keys = values.getList("keyframes");

        if (keys != null)
        {
            for (BaseType key : keys)
            {
                if (key.isMap() && locked(ranges, key.asMap().getFloat("tick")))
                {
                    return true;
                }
            }
        }

        ListType loops = values.getList("loops");

        if (loops != null)
        {
            for (BaseType entry : loops)
            {
                if (!entry.isMap())
                {
                    continue;
                }

                MapType loop = entry.asMap();
                float start = loop.getFloat("start");
                float end = loop.has("end") ? loop.getFloat("end") : loop.getFloat("source_end");

                if (intersects(ranges, start, end))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean intersects(List<KeyframeLockData.Range> ranges, float start, float end)
    {
        for (KeyframeLockData.Range range : ranges)
        {
            if (range.intersects(start, end))
            {
                return true;
            }
        }

        return false;
    }

    /** Yellow toast for a rollback that was refused because it would touch locked keyframes. */
    public static void notifyUndoBlocked()
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player != null)
        {
            client.player.sendMessage(Text.translatable("bbs_together.keyframe_lock.notify.undo_blocked").formatted(Formatting.YELLOW), true);
        }
    }

    private static boolean locked(List<KeyframeLockData.Range> ranges, float tick)
    {
        for (KeyframeLockData.Range range : ranges)
        {
            if (range.contains(tick))
            {
                return true;
            }
        }

        return false;
    }
}
