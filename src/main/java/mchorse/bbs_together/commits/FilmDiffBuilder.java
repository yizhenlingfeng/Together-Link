package mchorse.bbs_together.commits;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Data level diff between two film snapshots.
 *
 * <p>Snapshots are plain {@link MapType} trees (what {@code Film.toData()} writes, which is also
 * what sits in the film file), so this runs on the server without loading a {@code Film}, and it
 * does not break when a clip or a track type is added by an addon: unknown values are compared as
 * data and reported by their key.</p>
 *
 * <p>The one thing data level diffing cannot do by itself is tell which camera clip is which,
 * because {@code Clips} renumbers its children by position on every change and a {@code Clip}
 * carries no id. {@link #matchClips(List, List)} does that with the three pass rule from the plan:
 * type + title, then type + layer + tick within tolerance, then LCS over the rest.</p>
 */
public final class FilmDiffBuilder
{
    /** Field name used by BBS FS for the list of camera clips of a film. */
    public static final String CAMERA = "camera";

    /** Field name used by BBS FS for the list of replays of a film. */
    public static final String REPLAYS = "replays";

    /** Field name used by BBS FS for the markers of a film. */
    public static final String MARKERS = "markers";

    /** Field name used by BBS FS for the replay categories of a film. */
    public static final String CATEGORIES = "replay_categories";

    /** Key a stable id is stored under inside an element of a {@code ValueStableList}. */
    public static final String ID = "id";

    /** Key of the keyframe list inside a serialized {@code KeyframeChannel}. */
    public static final String KEYFRAMES = "keyframes";

    /** Key of the track list inside a serialized {@code FormProperties}. */
    public static final String TRACKS = "tracks";

    /** Key of the serialized channel inside one entry of {@link #TRACKS}. */
    public static final String CHANNEL = "channel";

    /** Key of the serialized action clips of a replay. */
    public static final String ACTIONS = "actions";

    /** How many ticks two clips may drift apart and still count as the same clip. */
    public static final int TICK_TOLERANCE = 2;

    /** Upper bound on reported entries, so a huge film cannot blow up the packet. */
    public static final int MAX_ENTRIES = 4096;

    private final MapType stats = new MapType();
    private final ListType cameras = new ListType();
    private final ListType replays = new ListType();
    private final ListType film = new ListType();
    private final ListType markers = new ListType();
    private final ListType categories = new ListType();

    private int entries;
    private boolean truncated;

    private FilmDiffBuilder()
    {}

    /**
     * Diff two film snapshots.
     *
     * @param before the older snapshot, may be empty for the first commit of a film
     * @param after  the newer snapshot
     */
    public static MapType diff(MapType before, MapType after)
    {
        FilmDiffBuilder builder = new FilmDiffBuilder();

        builder.run(before == null ? new MapType() : before, after == null ? new MapType() : after);

        return builder.output();
    }

    private void run(MapType before, MapType after)
    {
        this.diffCameras(before.getList(CAMERA), after.getList(CAMERA));
        this.diffReplays(before.getList(REPLAYS), after.getList(REPLAYS));
        this.diffGenericList(MARKERS, before.getList(MARKERS), after.getList(MARKERS), this.markers);
        this.diffGenericList(CATEGORIES, before.getList(CATEGORIES), after.getList(CATEGORIES), this.categories);
        this.diffScalars("", before, after, this.film, 0);
    }

    /* Output */

    private MapType output()
    {
        MapType out = new MapType();

        out.putInt("v", 1);
        out.put("stats", this.stats);
        out.put("camera", this.cameras);
        out.put("replays", this.replays);
        out.put("film", this.film);
        out.put("markers", this.markers);
        out.put("categories", this.categories);
        out.putFloat("duration_before", this.stats.getFloat("duration_before", 0F));
        out.putFloat("duration_after", this.stats.getFloat("duration_after", 0F));

        if (this.truncated)
        {
            out.putBool("truncated", true);
        }

        return out;
    }

    private boolean room()
    {
        if (this.entries >= MAX_ENTRIES)
        {
            this.truncated = true;

            return false;
        }

        this.entries += 1;

        return true;
    }

    /* Camera clips */

    private void diffCameras(ListType before, ListType after)
    {
        this.stats.putFloat("duration_before", duration(before));
        this.stats.putFloat("duration_after", duration(after));

        List<MapType> oldClips = maps(before);
        List<MapType> newClips = maps(after);
        List<int[]> pairs = matchClips(oldClips, newClips);

        boolean[] usedOld = new boolean[oldClips.size()];
        boolean[] usedNew = new boolean[newClips.size()];

        for (int[] pair : pairs)
        {
            usedOld[pair[0]] = true;
            usedNew[pair[1]] = true;
        }

        for (int i = 0; i < oldClips.size(); i++)
        {
            if (!usedNewClip(usedOld, i) && this.room())
            {
                this.cameras.add(clipEntry(oldClips, i, null, -1, true));
                count(this.stats, "clips", "removed");
            }
        }

        for (int j = 0; j < newClips.size(); j++)
        {
            if (!usedOldClip(usedNew, j) && this.room())
            {
                this.cameras.add(clipEntry(newClips, j, null, -1, false));
                count(this.stats, "clips", "added");
            }
        }

        for (int[] pair : pairs)
        {
            if (!this.room())
            {
                break;
            }

            MapType entry = clipEntry(newClips, pair[1], oldClips.get(pair[0]), pair[0], false);

            if (entry.has("fields") || entry.has("channels") || entry.has("moved"))
            {
                this.cameras.add(entry);
                count(this.stats, "clips", "modified");
            }
        }
    }

    private static boolean usedNewClip(boolean[] usedOld, int i)
    {
        return usedOld[i];
    }

    private static boolean usedOldClip(boolean[] usedNew, int j)
    {
        return usedNew[j];
    }

    /**
     * One camera clip: either an addition/removal, or a modification of a matched pair.
     */
    private MapType clipEntry(List<MapType> clips, int index, MapType old, int oldIndex, boolean removed)
    {
        MapType clip = clips.get(index);
        MapType entry = new MapType();

        entry.putString("op", removed ? "remove" : old == null ? "add" : "modify");
        entry.putString("type", clip.getString("type"));
        entry.putString("title", clip.getString("title"));
        entry.putInt("layer", clip.getInt("layer"));
        entry.putFloat("tick", clip.getFloat("tick"));
        entry.putFloat("duration", clip.getFloat("duration"));

        if (old == null)
        {
            return entry;
        }

        MapType moved = new MapType();

        if (Math.abs(old.getFloat("tick") - clip.getFloat("tick")) > 0.0001F)
        {
            moved.putFloat("tick_before", old.getFloat("tick"));
            moved.putFloat("tick_after", clip.getFloat("tick"));
        }

        if (old.getInt("layer") != clip.getInt("layer"))
        {
            moved.putInt("layer_before", old.getInt("layer"));
            moved.putInt("layer_after", clip.getInt("layer"));
        }

        if (!moved.isEmpty())
        {
            entry.put("moved", moved);
        }

        if (!old.getString("title").equals(clip.getString("title")))
        {
            entry.putString("title_before", old.getString("title"));
        }

        ListType fields = new ListType();
        MapType channels = new MapType();
        MapType keyframes = new MapType();
        int[] counter = new int[4];

        this.diffScalars("", old, clip, fields, 1);
        this.diffChannels(old, clip, channels, counter);

        if (!channels.isEmpty())
        {
            entry.put("channels", channels);
        }

        if (counter[0] + counter[1] + counter[2] > 0)
        {
            putCounter(keyframes, counter);
            entry.put("keyframes", keyframes);

            this.stats.putInt("kf_added", this.stats.getInt("kf_added") + counter[0]);
            this.stats.putInt("kf_removed", this.stats.getInt("kf_removed") + counter[1]);
            this.stats.putInt("kf_modified", this.stats.getInt("kf_modified") + counter[2]);
        }

        if (!fields.isEmpty())
        {
            entry.put("fields", fields);
        }

        return entry;
    }

    /* Replays */

    private void diffReplays(ListType before, ListType after)
    {
        List<MapType> oldReplays = maps(before);
        List<MapType> newReplays = maps(after);
        Map<String, MapType> byId = new LinkedHashMap<>();
        Map<String, Integer> order = new LinkedHashMap<>();

        for (int i = 0; i < newReplays.size(); i++)
        {
            MapType replay = newReplays.get(i);

            byId.put(replay.getString(ID), replay);
            order.put(replay.getString(ID), i);
        }

        List<String> seen = new ArrayList<>();

        for (MapType old : oldReplays)
        {
            String id = old.getString(ID);

            seen.add(id);

            MapType now = byId.get(id);

            if (now == null)
            {
                if (this.room())
                {
                    this.replays.add(replayEntry(old, null));
                    count(this.stats, "replays", "removed");
                }

                continue;
            }

            if (this.room())
            {
                MapType entry = replayEntry(now, old);

                this.replays.add(entry);
                count(this.stats, "replays", "modified");
            }
        }

        for (MapType now : newReplays)
        {
            if (seen.contains(now.getString(ID)))
            {
                continue;
            }

            if (this.room())
            {
                this.replays.add(replayEntry(now, null));
                count(this.stats, "replays", "added");
            }
        }
    }

    private MapType replayEntry(MapType now, MapType old)
    {
        MapType entry = new MapType();

        entry.putString("id", now.getString(ID));
        entry.putString("op", old == null ? "add" : "modify");
        entry.putString("name", replayName(now));

        MapType form = now.getMap("form");
        String formId = form.getString("id", now.getString("form", ""));

        entry.putString("form", formId);

        if (old == null)
        {
            return entry;
        }

        String oldName = replayName(old);

        if (!oldName.equals(entry.getString("name")))
        {
            entry.putString("name_before", oldName);
        }

        MapType oldForm = old.getMap("form");
        String oldFormId = oldForm.getString("id", old.getString("form", ""));

        if (!oldFormId.equals(formId))
        {
            entry.putString("form_before", oldFormId);
        }

        ListType fields = new ListType();

        this.diffScalars(KEYFRAMES, old, now, new ListType(), 1);
        this.diffScalars("", old, now, fields, 1);

        if (!fields.isEmpty())
        {
            entry.put("fields", fields);
        }

        MapType channels = new MapType();
        MapType keyframes = new MapType();
        int[] counter = new int[4];

        this.diffChannels(old.getMap(KEYFRAMES), now.getMap(KEYFRAMES), channels, counter);

        if (!channels.isEmpty())
        {
            entry.put("keyframe_channels", channels);
            putCounter(keyframes, counter);
            entry.put("keyframes", keyframes);

            this.stats.putInt("kf_added", this.stats.getInt("kf_added") + counter[0]);
            this.stats.putInt("kf_removed", this.stats.getInt("kf_removed") + counter[1]);
            this.stats.putInt("kf_modified", this.stats.getInt("kf_modified") + counter[2]);
        }

        this.diffTracks(old.getMap("properties").getList(TRACKS), now.getMap("properties").getList(TRACKS), entry);
        this.diffActions(old.getList(ACTIONS), now.getList(ACTIONS), entry);

        return entry;
    }

    private static String replayName(MapType replay)
    {
        String label = replay.getString("label", "");

        if (!label.isEmpty())
        {
            return label;
        }

        String form = replay.getMap("form").getString("form", "");

        return form.isEmpty() ? "-" : form;
    }

    private void diffTracks(ListType before, ListType after, MapType replayEntry)
    {
        List<MapType> oldTracks = maps(before);
        List<MapType> newTracks = maps(after);
        Map<String, MapType> oldByKey = new LinkedHashMap<>();

        for (MapType track : oldTracks)
        {
            oldByKey.put(trackKey(track), track);
        }

        List<String> seen = new ArrayList<>();
        ListType tracks = new ListType();

        for (MapType track : newTracks)
        {
            String key = trackKey(track);

            seen.add(key);

            MapType old = oldByKey.get(key);
            MapType channels = new MapType();
            MapType keyframes = new MapType();
            int[] counter = new int[4];

            this.diffChannels(old == null ? new MapType() : old.getMap(CHANNEL), track.getMap(CHANNEL), channels, counter);

            if (old != null && channels.isEmpty())
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("op", old == null ? "add" : "modify");
            entry.putString("kind", track.getString("kind"));
            entry.putString("form", track.getString("form", ""));
            entry.putString("subject", track.getString("subject", ""));
            entry.putString("prop", track.getString("prop", ""));
            putCounter(keyframes, counter);
            entry.put("keyframes", keyframes);

            if (!channels.isEmpty())
            {
                entry.put("channels", channels);
                entry.putFloat("from", channels.size() > 0 ? rangeFrom(track.getMap(CHANNEL)) : 0F);
                entry.putFloat("to", rangeTo(track.getMap(CHANNEL)));
            }

            tracks.add(entry);
            count(this.stats, "tracks", old == null ? "added" : "modified");

            this.stats.putInt("kf_added", this.stats.getInt("kf_added") + counter[0]);
            this.stats.putInt("kf_removed", this.stats.getInt("kf_removed") + counter[1]);
            this.stats.putInt("kf_modified", this.stats.getInt("kf_modified") + counter[2]);
        }

        for (MapType track : oldTracks)
        {
            String key = trackKey(track);

            if (seen.contains(key))
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("op", "remove");
            entry.putString("kind", track.getString("kind"));
            entry.putString("form", track.getString("form", ""));
            entry.putString("subject", track.getString("subject", ""));
            entry.putString("prop", track.getString("prop", ""));

            tracks.add(entry);
            count(this.stats, "tracks", "removed");
        }

        if (!tracks.isEmpty())
        {
            replayEntry.put("tracks", tracks);
        }
    }

    /** Tracks are addressed by kind + form path + subject + property, never by position. */
    private static String trackKey(MapType track)
    {
        return track.getString("kind") + "\u0000" + track.getString("form", "") + "\u0000"
            + track.getString("subject", "") + "\u0000" + track.getString("prop", "");
    }

    private void diffActions(ListType before, ListType after, MapType replayEntry)
    {
        List<MapType> oldClips = maps(before);
        List<MapType> newClips = maps(after);
        List<int[]> pairs = matchClips(oldClips, newClips);
        ListType actions = new ListType();

        boolean[] usedOld = new boolean[oldClips.size()];
        boolean[] usedNew = new boolean[newClips.size()];

        for (int[] pair : pairs)
        {
            usedOld[pair[0]] = true;
            usedNew[pair[1]] = true;

            MapType entry = clipEntry(newClips, pair[1], oldClips.get(pair[0]), pair[0], false);

            if (entry.has("fields") || entry.has("channels") || entry.has("moved") || entry.has("title_before"))
            {
                actions.add(entry);
            }
        }

        for (int i = 0; i < oldClips.size(); i++)
        {
            if (!usedOld[i])
            {
                actions.add(clipEntry(oldClips, i, null, -1, true));
                count(this.stats, "actions", "removed");
            }
        }

        for (int j = 0; j < newClips.size(); j++)
        {
            if (!usedNew[j])
            {
                actions.add(clipEntry(newClips, j, null, -1, false));
                count(this.stats, "actions", "added");
            }
        }

        if (!actions.isEmpty())
        {
            replayEntry.put("actions", actions);
        }
    }

    /* Keyframe channels */

    /**
     * Diff every channel found directly inside a data map (a clip's channels, a replay's keyframe
     * channels, a track's channel map). A channel is a map holding a {@code keyframes} list.
     */
    private void diffChannels(MapType before, MapType after, MapType out, int[] counter)
    {
        for (String key : union(before, after))
        {
            MapType a = before.getMap(key, null);
            MapType b = after.getMap(key, null);

            boolean isChannel = (a != null && a.has(KEYFRAMES)) || (b != null && b.has(KEYFRAMES));

            if (!isChannel)
            {
                continue;
            }

            MapType result = diffChannel(a, b, counter);

            if (result != null)
            {
                out.put(key, result);
            }
        }
    }

    private static MapType diffChannel(MapType before, MapType after, int[] counter)
    {
        TreeMap<Float, MapType> oldKeys = keyframesByTick(before);
        TreeMap<Float, MapType> newKeys = keyframesByTick(after);
        int added = 0;
        int removed = 0;
        int modified = 0;
        float from = Float.POSITIVE_INFINITY;
        float to = Float.NEGATIVE_INFINITY;

        for (Map.Entry<Float, MapType> entry : newKeys.entrySet())
        {
            MapType old = oldKeys.get(entry.getKey());

            if (old == null)
            {
                added += 1;
                from = Math.min(from, entry.getKey());
                to = Math.max(to, entry.getKey());
            }
            else if (!BaseType.equals(old, entry.getValue()))
            {
                modified += 1;
                from = Math.min(from, entry.getKey());
                to = Math.max(to, entry.getKey());
            }
        }

        for (Float tick : oldKeys.keySet())
        {
            if (!newKeys.containsKey(tick))
            {
                removed += 1;
                from = Math.min(from, tick);
                to = Math.max(to, tick);
            }
        }

        if (added == 0 && removed == 0 && modified == 0)
        {
            return null;
        }

        counter[0] += added;
        counter[1] += removed;
        counter[2] += modified;

        if (added > 0)
        {
            counter[3] += 1;
        }

        MapType out = new MapType();

        out.putInt("added", added);
        out.putInt("removed", removed);
        out.putInt("modified", modified);
        out.putFloat("from", from == Float.POSITIVE_INFINITY ? 0F : from);
        out.putFloat("to", to == Float.NEGATIVE_INFINITY ? 0F : to);

        if (before != null && after != null && !before.getString("type").equals(after.getString("type")))
        {
            out.putString("type_before", before.getString("type"));
            out.putString("type_after", after.getString("type"));
        }

        return out;
    }

    private static TreeMap<Float, MapType> keyframesByTick(MapType channel)
    {
        TreeMap<Float, MapType> map = new TreeMap<>();

        if (channel == null)
        {
            return map;
        }

        for (BaseType entry : channel.getList(KEYFRAMES))
        {
            if (!entry.isMap())
            {
                continue;
            }

            MapType keyframe = entry.asMap();

            map.put(keyframe.getFloat("tick"), keyframe);
        }

        return map;
    }

    private static float rangeFrom(MapType channels)
    {
        float min = Float.POSITIVE_INFINITY;

        for (String key : channels.keys())
        {
            min = Math.min(min, channels.getMap(key, null) == null ? 0F : channels.getMap(key).getFloat("from"));
        }

        return min == Float.POSITIVE_INFINITY ? 0F : min;
    }

    private static float rangeTo(MapType channels)
    {
        float max = Float.NEGATIVE_INFINITY;

        for (String key : channels.keys())
        {
            max = Math.max(max, channels.getMap(key, null) == null ? 0F : channels.getMap(key).getFloat("to"));
        }

        return max == Float.NEGATIVE_INFINITY ? 0F : max;
    }

    /* Scalar fields */

    /**
     * Collect scalar differences of two maps as {@code {k, old, new}} entries. Nested maps are
     * walked one level deep (that is where a clip keeps its envelope and a replay its form
     * settings), channels and lists are left to the callers that know what they mean.
     */
    private void diffScalars(String prefix, MapType before, MapType after, ListType out, int depth)
    {
        for (String key : union(before, after))
        {
            if (isStructural(key))
            {
                continue;
            }

            BaseType a = before.get(key);
            BaseType b = after.get(key);

            if ((a != null && a.isMap() && a.asMap().has(KEYFRAMES)) || (b != null && b.isMap() && b.asMap().has(KEYFRAMES)))
            {
                continue;
            }

            if (a != null && b != null && BaseType.equals(a, b))
            {
                continue;
            }

            if (a != null && a.isMap() && b != null && b.isMap() && depth > 0 && out != null)
            {
                this.diffScalars(prefix.isEmpty() ? key : prefix + "." + key, a.asMap(), b.asMap(), out, depth - 1);

                continue;
            }

            if (a != null && a.isList() && b != null && b.isList())
            {
                continue;
            }

            if (out == null)
            {
                continue;
            }

            if (!this.room())
            {
                return;
            }

            MapType change = new MapType();

            change.putString("k", prefix.isEmpty() ? key : prefix + "." + key);
            change.putString("old", describe(a));
            change.putString("new", describe(b));
            out.add(change);
        }
    }

    private static boolean isStructural(String key)
    {
        return key.equals(CAMERA) || key.equals(REPLAYS) || key.equals(MARKERS) || key.equals(CATEGORIES)
            || key.equals(ACTIONS) || key.equals(TRACKS) || key.equals(ID) || key.equals(KEYFRAMES);
    }

    /* Generic lists (markers, replay categories) */

    private void diffGenericList(String field, ListType before, ListType after, ListType out)
    {
        List<MapType> oldItems = maps(before);
        List<MapType> newItems = maps(after);

        if (oldItems.isEmpty() && newItems.isEmpty())
        {
            return;
        }

        List<int[]> pairs = matchClips(oldItems, newItems);
        boolean[] usedOld = new boolean[oldItems.size()];
        boolean[] usedNew = new boolean[newItems.size()];

        for (int[] pair : pairs)
        {
            usedOld[pair[0]] = true;
            usedNew[pair[1]] = true;

            MapType a = oldItems.get(pair[0]);
            MapType b = newItems.get(pair[1]);

            if (BaseType.equals(a, b) || !this.room())
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("op", "modify");
            entry.putString("label", itemLabel(b));
            out.add(entry);
            count(this.stats, field, "modified");
        }

        for (int i = 0; i < oldItems.size(); i++)
        {
            if (usedOld[i] || !this.room())
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("op", "remove");
            entry.putString("label", itemLabel(oldItems.get(i)));
            out.add(entry);
            count(this.stats, field, "removed");
        }

        for (int j = 0; j < newItems.size(); j++)
        {
            if (usedNew[j] || !this.room())
            {
                continue;
            }

            MapType entry = new MapType();

            entry.putString("op", "add");
            entry.putString("label", itemLabel(newItems.get(j)));
            out.add(entry);
            count(this.stats, field, "added");
        }
    }

    private static String itemLabel(MapType item)
    {
        String title = item.getString("title", "");

        if (!title.isEmpty())
        {
            return title;
        }

        String name = item.getString("name", "");

        if (!name.isEmpty())
        {
            return name;
        }

        return item.getString("type", "?");
    }

    /* Clip matching */

    /**
     * Match two lists of clips.
     *
     * <p>Pass 1 pairs clips by (type, title). Pass 2 pairs what is left by (type, layer) with the
     * start tick within {@link #TICK_TOLERANCE}, which is what a clip dragged by a few frames looks
     * like. Pass 3 runs an LCS over the remaining order, so a clip that was moved far, retitled and
     * changed is still reported as one modified clip instead of one removal plus one addition.</p>
     *
     * @return pairs of {@code [oldIndex, newIndex]}
     */
    public static List<int[]> matchClips(List<MapType> oldClips, List<MapType> newClips)
    {
        List<int[]> pairs = new ArrayList<>();
        boolean[] usedOld = new boolean[oldClips.size()];
        boolean[] usedNew = new boolean[newClips.size()];

        /* Pass 1: type + title */
        for (int i = 0; i < oldClips.size(); i++)
        {
            for (int j = 0; j < newClips.size(); j++)
            {
                if (usedNew[j])
                {
                    continue;
                }

                if (sameIdentity(oldClips.get(i), newClips.get(j)))
                {
                    pairs.add(new int[] {i, j});
                    usedOld[i] = true;
                    usedNew[j] = true;

                    break;
                }
            }
        }

        /* Pass 2: type + layer + tick tolerance */
        for (int i = 0; i < oldClips.size(); i++)
        {
            if (usedOld[i])
            {
                continue;
            }

            MapType old = oldClips.get(i);
            int best = -1;
            float bestDelta = Float.MAX_VALUE;

            for (int j = 0; j < newClips.size(); j++)
            {
                if (usedNew[j])
                {
                    continue;
                }

                MapType now = newClips.get(j);

                if (!old.getString("type").equals(now.getString("type")) || old.getInt("layer") != now.getInt("layer"))
                {
                    continue;
                }

                float delta = Math.abs(old.getFloat("tick") - now.getFloat("tick"));

                if (delta <= TICK_TOLERANCE && delta < bestDelta)
                {
                    best = j;
                    bestDelta = delta;
                }
            }

            if (best >= 0)
            {
                pairs.add(new int[] {i, best});
                usedOld[i] = true;
                usedNew[best] = true;
            }
        }

        /* Pass 3: LCS over the leftovers, in order */
        List<Integer> oldLeft = new ArrayList<>();
        List<Integer> newLeft = new ArrayList<>();

        for (int i = 0; i < oldClips.size(); i++)
        {
            if (!usedOld[i])
            {
                oldLeft.add(i);
            }
        }

        for (int j = 0; j < newClips.size(); j++)
        {
            if (!usedNew[j])
            {
                newLeft.add(j);
            }
        }

        for (int[] pair : lcsPairs(oldClips, newClips, oldLeft, newLeft))
        {
            pairs.add(pair);
        }

        return pairs;
    }

    private static boolean sameIdentity(MapType a, MapType b)
    {
        return a.getString("type").equals(b.getString("type")) && a.getString("title").equals(b.getString("title"));
    }

    private static List<int[]> lcsPairs(List<MapType> oldClips, List<MapType> newClips, List<Integer> oldLeft, List<Integer> newLeft)
    {
        int n = oldLeft.size();
        int m = newLeft.size();
        int[][] table = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--)
        {
            for (int j = m - 1; j >= 0; j--)
            {
                if (sameIdentity(oldClips.get(oldLeft.get(i)), newClips.get(newLeft.get(j))))
                {
                    table[i][j] = table[i + 1][j + 1] + 1;
                }
                else
                {
                    table[i][j] = Math.max(table[i + 1][j], table[i][j + 1]);
                }
            }
        }

        List<int[]> pairs = new ArrayList<>();
        int i = 0;
        int j = 0;

        while (i < n && j < m)
        {
            if (sameIdentity(oldClips.get(oldLeft.get(i)), newClips.get(newLeft.get(j))))
            {
                pairs.add(new int[] {oldLeft.get(i), newLeft.get(j)});
                i += 1;
                j += 1;
            }
            else if (table[i + 1][j] >= table[i][j + 1])
            {
                i += 1;
            }
            else
            {
                j += 1;
            }
        }

        return pairs;
    }

    /* Helpers */

    private static List<MapType> maps(ListType list)
    {
        List<MapType> out = new ArrayList<>();

        for (BaseType entry : list)
        {
            if (entry.isMap())
            {
                out.add(entry.asMap());
            }
        }

        return out;
    }

    private static List<String> union(MapType a, MapType b)
    {
        List<String> keys = new ArrayList<>();

        for (String key : a.keys())
        {
            keys.add(key);
        }

        for (String key : b.keys())
        {
            if (!keys.contains(key))
            {
                keys.add(key);
            }
        }

        return keys;
    }

    private static float duration(ListType clips)
    {
        float max = 0F;

        for (BaseType entry : clips)
        {
            if (!entry.isMap())
            {
                continue;
            }

            MapType clip = entry.asMap();

            max = Math.max(max, clip.getFloat("tick") + clip.getFloat("duration"));
        }

        return max;
    }

    private static void count(MapType stats, String group, String action)
    {
        MapType map = stats.getMap(group, null);

        if (map == null)
        {
            map = new MapType();
            stats.put(group, map);
        }

        map.putInt(action, map.getInt(action) + 1);
    }

    private static void putCounter(MapType out, int[] counter)
    {
        out.putInt("added", counter[0]);
        out.putInt("removed", counter[1]);
        out.putInt("modified", counter[2]);
    }

    /**
     * Human readable rendering of a value for the details tree. Numeric values lose the trailing
     * {@code .0} so an int and a float that hold the same number look the same.
     */
    public static String describe(BaseType value)
    {
        if (value == null)
        {
            return "";
        }

        if (value.isString())
        {
            return value.asString();
        }

        if (value.isNumeric())
        {
            double number = value.asNumeric().doubleValue();

            if (number == Math.rint(number) && !Double.isInfinite(number) && Math.abs(number) < 1.0E7D)
            {
                return String.valueOf((long) number);
            }

            return String.valueOf((float) number);
        }

        String rendered = value.toString();

        return rendered.length() > 80 ? rendered.substring(0, 77) + "..." : rendered;
    }
}
