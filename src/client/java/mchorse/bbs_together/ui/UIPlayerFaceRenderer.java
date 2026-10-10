package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.resources.PlayerSkins;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a player's face in a small square, the way the vanilla tab list does:
 * the head's base layer, then the hat layer on top of it. Online players use
 * the skin the vanilla client already has; offline players fall back to BBS's
 * player skins cache; while nothing has loaded a neutral placeholder shows.
 *
 * <p>UVs are always normalized against a 64x64 skin: skins come either in the
 * standard layout or as an HD version that is the same layout scaled by a
 * whole factor, so the relative coordinates are the same for both.</p>
 */
public class UIPlayerFaceRenderer
{
    /** Skin size UVs are relative to, whatever the texture's actual size. */
    private static final int SKIN = 64;
    /** The head's base face: 8x8 at (8, 8). */
    private static final int FACE_U1 = 8;
    private static final int FACE_V1 = 8;
    private static final int FACE_U2 = 16;
    private static final int FACE_V2 = 16;
    /** The hat layer's front: 8x8 at (40, 8), drawn over the face. */
    private static final int HAT_U1 = 40;
    private static final int HAT_V1 = 8;
    private static final int HAT_U2 = 48;
    private static final int HAT_V2 = 16;

    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    public static void draw(Batcher2D batcher, String name, UUID uuid, int x, int y, int size)
    {
        /* Online players: the skin the vanilla client already has loaded */
        if (uuid != null && drawVanilla(batcher, uuid, x, y, size))
        {
            return;
        }

        /* Offline players: BBS's cached skins */
        if (name != null && !name.isEmpty() && drawCached(batcher, name, x, y, size))
        {
            return;
        }

        drawPlaceholder(batcher, x, y, size);
    }

    /** The two-layer face of a player currently on the server, out of the vanilla skin. */
    private static boolean drawVanilla(Batcher2D batcher, UUID uuid, int x, int y, int size)
    {
        try
        {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.getNetworkHandler() == null)
            {
                return false;
            }

            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);

            if (entry == null)
            {
                return false;
            }

            Identifier skin = entry.getSkinTexture();

            if (skin == null)
            {
                return false;
            }

            int glId = mc.getTextureManager().getTexture(skin).getGlId();

            drawFace(batcher, glId, x, y, size);

            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * A two-layer face out of BBS's player skins cache. The texture only
     * counts once it has skin dimensions — a just-created texture or the
     * wrong asset would otherwise paint garbage pixels. Standard skins are
     * 64x64, HD ones are the same layout scaled by a whole factor.
     */
    private static boolean drawCached(Batcher2D batcher, String name, int x, int y, int size)
    {
        try
        {
            Link link = PlayerSkins.link(name);
            Texture texture = BBSModClient.getTextures().getTexture(link, GL11.GL_NEAREST, true);

            if (texture != null && texture.isValid() && texture.width >= 64 && texture.width % 64 == 0 && texture.height % 64 == 0)
            {
                drawFace(batcher, texture.id, x, y, size);

                return true;
            }

            if (requested.add(name))
            {
                PlayerSkins.request(link, name, (loaded) -> {});
            }
        }
        catch (Exception ignored)
        {}

        return false;
    }

    /** Face base + hat layer, both normalized against the 64x64 layout. */
    private static void drawFace(Batcher2D batcher, int glId, int x, int y, int size)
    {
        batcher.texturedBox(glId, -1, x, y, size, size, FACE_U1, FACE_V1, FACE_U2, FACE_V2, SKIN, SKIN);
        batcher.texturedBox(glId, -1, x, y, size, size, HAT_U1, HAT_V1, HAT_U2, HAT_V2, SKIN, SKIN);
    }

    private static void drawPlaceholder(Batcher2D batcher, int x, int y, int size)
    {
        batcher.box(x, y, x + size, y + size, Colors.A50 | Colors.GRAY);

        int icon = Math.max(8, size - 4);

        batcher.icon(Icons.USER, Colors.LIGHTER_GRAY, x + (size - icon) / 2, y + (size - icon) / 2);
    }
}
