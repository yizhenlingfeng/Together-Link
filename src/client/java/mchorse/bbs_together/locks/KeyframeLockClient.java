package mchorse.bbs_together.locks;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeSelection;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.KeyframeType;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_together.BBSTogetherFix22;
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
import java.util.WeakHashMap;

/**
 * Client side of the multi-keyframe lock. Lock records live in the foreign map of the
 * nearest replay or camera clip. Local writes are refused at the UI entry points and
 * re-checked in channel transactions; authoritative remote writes use the remote-apply
 * boundary, and the server independently re-checks every write through {@link KeyframeLockGuard}.
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

    private static final Map<KeyframeChannel<?>, ChannelEdit> channelEdits = new WeakHashMap<>();
    private static final ThreadLocal<Integer> restoring = ThreadLocal.withInitial(() -> 0);

    private static final class ChannelEdit
    {
        private int depth = 1;
        private final BaseType before;
        private final List<Keyframe<?>> lockedKeys;

        private ChannelEdit(BaseType before, List<Keyframe<?>> lockedKeys)
        {
            this.before = before;
            this.lockedKeys = lockedKeys;
        }
    }

    public static void beginChannelEdit(KeyframeChannel<?> channel)
    {
        ChannelEdit edit = channelEdits.get(channel);

        if (edit != null)
        {
            edit.depth += 1;
            return;
        }

        if (!guardsLocalWrites())
        {
            return;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(channel);
        List<KeyframeLockData.Range> ranges = owner == null ? List.of() : KeyframeLockData.rangesFor(owner, channel.getId());

        if (ranges.isEmpty())
        {
            return;
        }

        ArrayList<Keyframe<?>> lockedKeys = new ArrayList<>();

        for (Keyframe<?> key : channel.getKeyframes())
        {
            if (locked(ranges, key.getTick()))
            {
                lockedKeys.add(key);
            }
        }

        channelEdits.put(channel, new ChannelEdit(channel.toData(), lockedKeys));
    }

    public static void endChannelEdit(KeyframeChannel<?> channel)
    {
        ChannelEdit edit = channelEdits.get(channel);

        if (edit == null)
        {
            return;
        }

        edit.depth -= 1;

        if (edit.depth > 0)
        {
            return;
        }

        channelEdits.remove(channel);

        if (BaseType.equals(edit.before, channel.toData()))
        {
            return;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(channel);
        List<KeyframeLockData.Range> ranges = owner == null ? List.of() : KeyframeLockData.rangesFor(owner, channel.getId());

        if (!ranges.isEmpty())
        {
            restoreChannel(channel, edit, ranges);
        }
    }

    private static boolean guardsLocalWrites()
    {
        return !BBSTogetherFix22.isApplyingRemoteSync() && restoring.get() == 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreChannel(KeyframeChannel<?> channel, ChannelEdit edit, List<KeyframeLockData.Range> ranges)
    {
        KeyframeChannel stored = new KeyframeChannel(channel.getId(), channel.getFactory());

        stored.fromData(edit.before);
        restoring.set(restoring.get() + 1);

        try
        {
            boolean replaced = false;

            for (Keyframe<?> key : edit.lockedKeys)
            {
                if (!containsIdentity(channel, key))
                {
                    replaced = true;
                    break;
                }
            }

            if (replaced)
            {
                replaceChannel(channel, stored);
                return;
            }

            for (int i = channel.getKeyframes().size() - 1; i >= 0; i--)
            {
                Keyframe<?> key = (Keyframe<?>) channel.getKeyframes().get(i);

                if (locked(ranges, key.getTick()) || containsIdentity(edit.lockedKeys, key))
                {
                    channel.remove(i);
                }
            }

            for (Object entry : stored.getKeyframes())
            {
                Keyframe key = (Keyframe) entry;

                if (locked(ranges, key.getTick()))
                {
                    channel.add(cloneKey(key));
                }
            }

            restoreLoops(channel, stored, ranges, false);
            channel.sort();
        }
        finally
        {
            restoring.set(restoring.get() - 1);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceChannel(KeyframeChannel<?> channel, KeyframeChannel stored)
    {
        for (int i = channel.getKeyframes().size() - 1; i >= 0; i--)
        {
            channel.remove(i);
        }

        for (Object key : stored.getKeyframes())
        {
            channel.add(cloneKey((Keyframe) key));
        }

        restoreLoops(channel, stored, List.of(), true);
        channel.sort();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restoreLoops(KeyframeChannel<?> channel, KeyframeChannel stored, List<KeyframeLockData.Range> ranges, boolean all)
    {
        for (KeyframeLoop loop : new ArrayList<>(channel.getLoops()))
        {
            if (all || intersects(ranges, loop.start(), channel.getLoopEnd(loop)))
            {
                channel.removeLoop(loop.id());
            }
        }

        for (Object entry : new ArrayList<>(stored.getLoops()))
        {
            KeyframeLoop loop = (KeyframeLoop) entry;

            if (all || intersects(ranges, loop.start(), stored.getLoopEnd(loop)))
            {
                channel.putLoop(loop);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Keyframe cloneKey(Keyframe key)
    {
        Keyframe copy = new Keyframe("", key.getFactory());

        copy.copy(key);

        return copy;
    }

    private static boolean containsIdentity(KeyframeChannel<?> channel, Keyframe<?> candidate)
    {
        for (Keyframe<?> key : channel.getKeyframes())
        {
            if (key == candidate)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean containsIdentity(List<Keyframe<?>> keys, Keyframe<?> candidate)
    {
        for (Keyframe<?> key : keys)
        {
            if (key == candidate)
            {
                return true;
            }
        }

        return false;
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

    /** Removes every locked key from the selection and reports whether anything was removed. */
    public static boolean filterLockedSelection(UIKeyframes view)
    {
        boolean removed = false;

        if (view == null || view.getGraph() == null)
        {
            return false;
        }

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
                removed = true;
            }
        }

        return removed;
    }

    public static List<KeyframeLockData.Range> rangesForSheet(UIKeyframeSheet sheet)
    {
        if (sheet == null || sheet.channel == null)
        {
            return List.of();
        }

        ValueGroup owner = KeyframeLockData.ownerOf(sheet.channel);

        return owner == null ? List.of() : KeyframeLockData.rangesFor(owner, sheet.channel.getId());
    }

    /* Local enforcement */

    /** Whether a key of a sheet sits inside a lock. */
    public static boolean lockedKey(UIKeyframeSheet sheet, Keyframe<?> key)
    {
        return sheet != null && key != null && locked(rangesForSheet(sheet), key.getTick());
    }

    public static boolean lockedKeyframe(Keyframe<?> key)
    {
        if (key == null || !(key.getParent() instanceof KeyframeChannel<?> channel))
        {
            return false;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(channel);
        List<KeyframeLockData.Range> ranges = owner == null ? List.of() : KeyframeLockData.rangesFor(owner, channel.getId());

        return locked(ranges, key.getTick());
    }

    public static boolean blocksKeyframeMutation(Keyframe<?> key, float targetTick)
    {
        if (!guardsLocalWrites() || key == null || !(key.getParent() instanceof KeyframeChannel<?> channel))
        {
            return false;
        }

        if (channelEdits.containsKey(channel))
        {
            return false;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(channel);
        List<KeyframeLockData.Range> ranges = owner == null ? List.of() : KeyframeLockData.rangesFor(owner, channel.getId());

        return locked(ranges, key.getTick()) || locked(ranges, targetTick);
    }

    public static boolean blocksKeyframeMutation(Keyframe<?> key)
    {
        return key != null && blocksKeyframeMutation(key, key.getTick());
    }

    /**
     * Drops locked keys from a click selection and reports whether the click landed on one of them.
     *
     * <p>BBS picks the key under the cursor and arms a drag on the same click, so the key is dropped
     * here - the mixin runs right after {@code UIKeyframes#subMouseClicked} - and the click is
     * reported as consumed. That leaves nothing for the following {@code handleMouse} to drag.</p>
     */
    public static boolean filterLockedClick(UIKeyframes view, int mouseX, int mouseY)
    {
        filterLockedSelection(view);

        return !hits(view, mouseX, mouseY).isEmpty();
    }

    /* Edit guards called from the timeline mixin */

    /**
     * Guard for Ctrl+click, which adds or removes the keyframe under the cursor.
     *
     * <p>{@code filterLockedClick} only helps when the click went through the pick path. Ctrl+click
     * takes the create/remove branch first, so a lock block with no keys inside could be filled, and
     * a locked key could be deleted. Both are caught here: removing needs the key itself locked,
     * creating needs the tick under the cursor to be inside a lock of that sheet.</p>
     */
    public static boolean blocksKeyframeEdit(UIKeyframes view, int mouseX, int mouseY)
    {
        IUIKeyframeGraph graph = view == null ? null : view.getGraph();

        if (graph == null)
        {
            return false;
        }

        Pair<Keyframe, KeyframeType> pair = (Pair<Keyframe, KeyframeType>) graph.findKeyframe(mouseX, mouseY);

        if (pair != null && lockedKey(graph.getSheet(pair.a), pair.a))
        {
            return true;
        }

        /* Nothing under the cursor: the click would insert, which a lock block forbids as well. */
        return pair == null && lockedTick(graph.getSheet(mouseY), (float) view.fromGraphX(mouseX));
    }

    public static boolean lockedTick(KeyframeChannel<?> channel, float tick)
    {
        if (channel == null)
        {
            return false;
        }

        ValueGroup owner = KeyframeLockData.ownerOf(channel);
        List<KeyframeLockData.Range> ranges = owner == null ? List.of() : KeyframeLockData.rangesFor(owner, channel.getId());

        return locked(ranges, tick);
    }

    /** Whether a tick of a sheet falls inside a lock that applies to the local player. */
    public static boolean lockedTick(UIKeyframeSheet sheet, float tick)
    {
        return sheet != null && lockedTick(sheet.channel, tick);
    }

    /* Hit testing */

    public record HitRecord(ValueGroup owner, KeyframeLockData lock, KeyframeLockData.Range range, int y, int height)
    {}

    public static boolean isSheetVisible(UIKeyframes view, UIKeyframeSheet sheet)
    {
        return view != null && sheet != null && view.getGraph() != null
            && view.getGraph().getSheet(sheet.id) == sheet;
    }

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

    public static boolean loopLocked(KeyframeChannel<?> channel, String id)
    {
        if (channel == null || id == null)
        {
            return false;
        }

        KeyframeLoop loop = channel.getLoop(id);

        return loop != null && KeyframeLockData.loopLocked(KeyframeLockData.ownerOf(channel), channel.getId(), loop, channel.getLoopEnd(loop));
    }

    /** Whether a loop block of a sheet reacts to the mouse at {@code mouseX}, using BBS' own grab margins. */
    public static boolean loopLocked(UIKeyframes view, UIKeyframeSheet sheet, int mouseX)
    {
        if (sheet == null)
        {
            return false;
        }

        for (Object entry : sheet.channel.getLoops())
        {
            KeyframeLoop loop = (KeyframeLoop) entry;
            int x1 = view.toGraphX(loop.start());
            int x2 = view.toGraphX(sheet.channel.getLoopEnd(loop));

            if (mouseX >= x1 - 3 && mouseX <= x2 + 4 && loopLocked(sheet.channel, loop.id()))
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
            if (!isSheetVisible(view, sheet))
            {
                continue;
            }

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
            float restoredTick = value.isMap() && value.asMap().has("tick") ? value.asMap().getFloat("tick") : keyframe.getTick();

            return locked(ranges, keyframe.getTick()) || locked(ranges, restoredTick);
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
