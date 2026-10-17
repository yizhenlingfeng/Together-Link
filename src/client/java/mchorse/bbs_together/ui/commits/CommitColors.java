package mchorse.bbs_together.ui.commits;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Colour of each commit, picked by the user in the commit graph.
 *
 * <p>The choice is kept on this client only (it is a reading aid, not part of the film), in
 * {@code config/bbs_together_commit_colors.json}. Every commit starts gray; the connecting lines
 * stay dark gray so a colour the user picked is the one thing that stands out.</p>
 */
public final class CommitColors
{
    /** Colour of a commit nobody has recoloured yet. */
    public static final int DEFAULT = Colors.GRAY;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Integer> COLORS = new HashMap<String, Integer>();

    private static boolean loaded;

    private CommitColors()
    {}

    public static int get(String filmId, String commitId)
    {
        load();

        Integer color = COLORS.get(key(filmId, commitId));

        return color == null ? DEFAULT : color;
    }

    public static void set(String filmId, String commitId, int color)
    {
        load();

        COLORS.put(key(filmId, commitId), Colors.opaque(color));

        save();
    }

    /** Films can contain slashes, so the two halves get a character neither of them may hold. */
    private static String key(String filmId, String commitId)
    {
        return (filmId == null ? "" : filmId) + '\t' + (commitId == null ? "" : commitId);
    }

    private static File file()
    {
        return new File(FabricLoader.getInstance().getConfigDir().toFile(), "bbs_together_commit_colors.json");
    }

    private static void load()
    {
        if (loaded)
        {
            return;
        }

        loaded = true;

        File file = file();

        if (!file.isFile())
        {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
        {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            if (root == null)
            {
                return;
            }

            for (Map.Entry<String, JsonElement> entry : root.entrySet())
            {
                COLORS.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        catch (Exception e)
        {
            /* A broken file must not take the graph down with it: the colours just fall back */
        }
    }

    private static void save()
    {
        JsonObject root = new JsonObject();

        for (Map.Entry<String, Integer> entry : COLORS.entrySet())
        {
            root.addProperty(entry.getKey(), entry.getValue());
        }

        try
        {
            Files.writeString(file().toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            /* Nothing to do about an unwritable config directory; the colour still lives in memory */
        }
    }
}
