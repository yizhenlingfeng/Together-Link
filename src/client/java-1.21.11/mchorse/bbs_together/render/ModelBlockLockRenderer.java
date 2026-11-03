package mchorse.bbs_together.render;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.graphics.texture.AdoptedTexture;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.compat.ClientCompat;
import mchorse.bbs_together.locks.AssetLock;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 1.21.11 world-space lock badge.
 *
 * <p>The old renderer was an immediate-mode billboard called from the model block
 * entity renderer. 1.21.11 entity renderers only enqueue render commands, so this
 * renderer runs from {@code WorldRenderEvents.AFTER_ENTITIES}, where the entity queue
 * has flushed and the camera matrix is live. The icon atlas is a raw-GL BBS texture;
 * {@link AdoptedTexture} gives it an {@link Identifier} that an entity render layer can
 * sample.</p>
 */
@Environment(EnvType.CLIENT)
public final class ModelBlockLockRenderer
{
    private static final float BADGE_SIZE = 0.35F;
    private static final double MAX_DISTANCE_SQ = 64D * 64D;

    private ModelBlockLockRenderer()
    {}

    public static void render(WorldRenderContext context)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null || client.player == null || !TogetherClientNetwork.isConnected())
        {
            return;
        }

        if (!ClientCompat.isDebugHudVisible(client))
        {
            return;
        }

        Texture texture = BBSModClient.getTextures().getTexture(Icons.ATLAS, GL11.GL_NEAREST, true);

        if (texture == null || !texture.isValid())
        {
            return;
        }

        Identifier adopted = AdoptedTexture.identifier(texture);

        if (adopted == null)
        {
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        String dimension = client.world.getRegistryKey().getValue().toString();
        Icon icon = Icons.LOCKED;
        float u0 = icon.x / (float) icon.textureW;
        float u1 = (icon.x + icon.w) / (float) icon.textureW;
        float v0 = icon.y / (float) icon.textureH;
        float v1 = (icon.y + icon.h) / (float) icon.textureH;
        VertexConsumerProvider consumers = context.consumers();
        RenderLayer layer = RenderLayers.entityTranslucent(adopted);
        VertexConsumer builder = consumers.getBuffer(layer);
        MatrixStack matrices = context.matrices();
        Vec3d cameraPos = camera.getCameraPos();
        boolean drew = false;

        for (AssetLock lock : AssetLockClient.all())
        {
            if (lock == null || lock.type != AssetLockType.MODEL_BLOCK || lock.assetId == null)
            {
                continue;
            }

            if (!AssetLockClient.isLockedForSelf(AssetLockType.MODEL_BLOCK, lock.assetId)
                && !AssetLockClient.canManage())
            {
                continue;
            }

            BlockPos pos = parsePos(lock.assetId, dimension);

            if (pos == null || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4))
            {
                continue;
            }

            if (!(client.world.getBlockEntity(pos) instanceof ModelBlockEntity))
            {
                continue;
            }

            Vec3d center = Vec3d.ofCenter(pos);

            if (cameraPos.squaredDistanceTo(center) > MAX_DISTANCE_SQ)
            {
                continue;
            }

            /* Pull the badge toward the camera so the depth test does not hide it inside
             * the block. The old immediate renderer disabled depth entirely. */
            Vec3d offset = cameraPos.subtract(center).normalize().multiply(0.55D);
            Vec3d position = center.add(offset).subtract(cameraPos);

            matrices.push();
            matrices.translate(position.x, position.y, position.z);
            matrices.multiply(camera.getRotation());

            Matrix4f matrix = matrices.peek().getPositionMatrix();

            vertex(builder, matrix, -BADGE_SIZE, -BADGE_SIZE, u0, v1);
            vertex(builder, matrix, BADGE_SIZE, -BADGE_SIZE, u1, v1);
            vertex(builder, matrix, BADGE_SIZE, BADGE_SIZE, u1, v0);
            vertex(builder, matrix, -BADGE_SIZE, BADGE_SIZE, u0, v0);

            matrices.pop();
            drew = true;
        }

        if (drew && consumers instanceof VertexConsumerProvider.Immediate immediate)
        {
            immediate.draw(layer);
        }
    }

    private static void vertex(VertexConsumer builder, Matrix4f matrix, float x, float y, float u, float v)
    {
        builder.vertex(matrix, x, y, 0F)
            .color(0xFFFFFFFF)
            .texture(u, v)
            .overlay(OverlayTexture.DEFAULT_UV)
            .light(0xF000F0)
            .normal(0F, 0F, 1F);
    }

    private static BlockPos parsePos(String assetId, String dimension)
    {
        String[] parts = assetId.split("\\|", 4);

        if (parts.length != 4 || !parts[0].equals(dimension))
        {
            return null;
        }

        try
        {
            return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
