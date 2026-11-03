package mchorse.bbs_together.render;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws the little padlock shown inside a locked model block's F3 debug box.
 *
 * <p>A billboard: the quad is rotated by the camera's rotation, so it always turns its
 * face toward the player. The icon comes from BBS's own icon atlas, which is bound as a
 * plain GL texture — the atlas is not a vanilla {@code Identifier}.</p>
 */
@Environment(EnvType.CLIENT)
public final class ModelBlockLockRenderer
{
    private ModelBlockLockRenderer()
    {}

    public static void render(MatrixStack matrices, Camera camera)
    {
        Texture texture = BBSModClient.getTextures().getTexture(Icons.ATLAS, GL11.GL_NEAREST, true);

        if (texture == null || !texture.isValid())
        {
            return;
        }

        Icon icon = Icons.LOCKED;
        float u0 = icon.x / (float) icon.textureW;
        float u1 = (icon.x + icon.w) / (float) icon.textureW;
        float v0 = icon.y / (float) icon.textureH;
        float v1 = (icon.y + icon.h) / (float) icon.textureH;
        float s = 0.35F;

        matrices.push();
        matrices.translate(0.5F, 0.5F, 0.5F);
        matrices.multiply(camera.getRotation());

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        BufferBuilder builder = Tessellator.getInstance().getBuffer();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        BBSModClient.getTextures().bindTexture(texture);

        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

        builder.vertex(matrix, -s, -s, 0F).texture(u0, v1).color(1F, 1F, 1F, 1F).next();
        builder.vertex(matrix, s, -s, 0F).texture(u1, v1).color(1F, 1F, 1F, 1F).next();
        builder.vertex(matrix, s, s, 0F).texture(u1, v0).color(1F, 1F, 1F, 1F).next();
        builder.vertex(matrix, -s, s, 0F).texture(u0, v0).color(1F, 1F, 1F, 1F).next();

        BufferRenderer.drawWithGlobalProgram(builder.end());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        matrices.pop();
    }
}
