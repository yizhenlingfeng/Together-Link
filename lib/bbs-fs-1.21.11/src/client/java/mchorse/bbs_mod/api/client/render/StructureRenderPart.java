package mchorse.bbs_mod.api.client.render;

import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.Map;

/** An independently cached piece of a structure. Uses the normal block, block-entity,
 * shader and picking paths; never mutates the owner's renderer or the shared structure cache.
 * Client-thread only. Recreate when source data/biome changes; resource rebakes are automatic. */
public final class StructureRenderPart
{
    private final PartRenderer renderer;

    public StructureRenderPart(StructureForm owner, StructureRenderData data, String biome, Vector3f offset)
    {
        this.renderer = new PartRenderer(owner, data, biome, offset);
    }

    /** Defensive copies, including block entity NBT and mutable block positions. */
    public static StructureRenderData createData(String id, Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, NbtCompound> entities)
    {
        return StructureRenderData.create(id, size, blocks, entities);
    }

    public void render(FormRenderingContext context, Matrix4f transform)
    {
        context.stack.push();
        if (context.world != null) context.world.push();
        try
        {
            if (transform != null)
            {
                MatrixStackUtils.multiply(context.stack, transform);
                if (context.world != null) MatrixStackUtils.multiply(context.world, transform);
            }
            this.renderer.draw(context);
        }
        finally
        {
            context.stack.pop();
            if (context.world != null) context.world.pop();
        }
    }

    private static final class PartRenderer extends StructureFormRenderer
    {
        PartRenderer(StructureForm form, StructureRenderData data, String biome, Vector3f offset)
        {
            super(form, data, biome, offset);
        }
        void draw(FormRenderingContext context) { super.render3D(context); }
    }
}
