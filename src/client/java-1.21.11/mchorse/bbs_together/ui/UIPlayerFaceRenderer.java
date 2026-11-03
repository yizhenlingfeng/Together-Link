package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.resources.PlayerSkins;
import mchorse.bbs_together.compat.ClientCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.21.11 player face renderer. The vanilla skin still has a GL texture under the new
 * GPU abstraction; {@link GlTexture#getGlId()} exposes the id the BBS UI batcher
 * adopts. The cached/offline path is unchanged.
 */
public class UIPlayerFaceRenderer
{
    private static final int SKIN = 64;
    private static final int FACE_U1 = 8;
    private static final int FACE_V1 = 8;
    private static final int FACE_U2 = 16;
    private static final int FACE_V2 = 16;
    private static final int HAT_U1 = 40;
    private static final int HAT_V1 = 8;
    private static final int HAT_U2 = 48;
    private static final int HAT_V2 = 16;

    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    public static void draw(Batcher2D batcher, String name, UUID uuid, int x, int y, int size)
    {
        if (uuid != null && drawVanilla(batcher, uuid, x, y, size))
        {
            return;
        }

        if (name != null && !name.isEmpty() && drawCached(batcher, name, x, y, size))
        {
            return;
        }

        drawPlaceholder(batcher, x, y, size);
    }

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

            Identifier skin = ClientCompat.skinTexture(entry);

            if (skin == null)
            {
                return false;
            }

            AbstractTexture texture = mc.getTextureManager().getTexture(skin);

            if (!(texture.getGlTexture() instanceof GlTexture glTexture))
            {
                return false;
            }

            drawFace(batcher, glTexture.getGlId(), x, y, size);

            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

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
