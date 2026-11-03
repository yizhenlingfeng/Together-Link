package mchorse.bbs_mod.utils.keyframes;

import mchorse.bbs_mod.data.types.MapType;

/** A finite repetition of the channel's keys in [start, sourceEnd].
 * Channels belonging to the same block share an id, not mutable object identity. */
public record KeyframeLoop(String id, float start, float sourceEnd, float end)
{
    public boolean isValid()
    {
        return id != null && !id.isEmpty() && Float.isFinite(start)
            && Float.isFinite(sourceEnd) && Float.isFinite(end)
            && sourceEnd > start && end >= sourceEnd && Float.isFinite(period()) && Float.isFinite(end - start);
    }

    public float period()
    {
        return sourceEnd - start;
    }

    public float passes()
    {
        return (end - start) / period();
    }

    public boolean containsSource(float tick)
    {
        return tick >= start && tick <= sourceEnd;
    }

    public float sourceTick(float tick)
    {
        if (tick <= sourceEnd) return Math.max(start, tick);

        double offset = ((double) Math.min(tick, end) - start) % period();

        /* An exact boundary belongs to the completed pass, including the block's end. */
        return offset == 0 ? sourceEnd : (float) (start + offset);
    }

    public KeyframeLoop withEnd(float end)
    {
        return new KeyframeLoop(id, start, sourceEnd, Math.max(sourceEnd, end));
    }

    public KeyframeLoop move(float delta)
    {
        return new KeyframeLoop(id, start + delta, sourceEnd + delta, end + delta);
    }

    public MapType toData()
    {
        MapType data = new MapType();
        data.putString("id", id);
        data.putFloat("start", start);
        data.putFloat("source_end", sourceEnd);
        data.putFloat("end", end);
        return data;
    }

    public static KeyframeLoop fromData(MapType data)
    {
        return new KeyframeLoop(data.getString("id"), data.getFloat("start"), data.getFloat("source_end"), data.getFloat("end"));
    }
}
