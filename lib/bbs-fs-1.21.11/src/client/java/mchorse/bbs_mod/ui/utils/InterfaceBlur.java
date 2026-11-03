package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPipeline;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.UniformValue;
import net.minecraft.client.render.ProjectionMatrix2;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dual Kawase blur from bbs-refreshed-addon, using the deferred GUI blur marker on 1.21.11.
 * The topmost caller owns the single blur layer; the post pipeline runs after earlier GUI layers.
 * See assets/bbs/licenses/bbs-refreshed-addon.txt for the MIT license.
 */
public class InterfaceBlur
{
    private static final Identifier SCREEN_QUAD = Identifier.of("minecraft", "core/screenquad");
    private static final Identifier DOWN = Identifier.of(BBSMod.MOD_ID, "post/kawase_down");
    private static final Identifier UP = Identifier.of(BBSMod.MOD_ID, "post/kawase_up");

    private static final int MAX_LEVELS = 5;
    /** A level smaller than this on either side is not worth a pass (and would smear the edges). */
    private static final int MIN_SIZE = 16;

    /** Offsets the {@link #SIGMA} table was sampled at. */
    private static final float[] OFFSETS = {0.5F, 1.0F, 1.5F, 2.0F, 2.5F, 3.0F};
    /** Offsets past this start to show the tap pattern; a stronger blur takes one more level instead. */
    private static final float MAX_CLEAN_OFFSET = 1.5F;
    private static final float MIN_OFFSET = 0.25F;

    /**
     * Blur strength per level count (rows, 1..5) and offset (columns, {@link #OFFSETS}): the standard deviation
     * along one axis of the whole chain's impulse response, in full-resolution pixels. Simulated with bilinear
     * sampling and averaged over two impulse phases.
     */
    private static final float[][] SIGMA = {
        {0.96F, 1.44F, 2.01F, 2.61F, 3.14F, 3.71F},
        {2.14F, 3.23F, 4.50F, 5.85F, 7.04F, 8.35F},
        {4.39F, 6.61F, 9.33F, 11.98F, 14.34F, 17.02F},
        {8.83F, 13.31F, 18.95F, 24.10F, 28.75F, 34.08F},
        {17.68F, 26.65F, 38.17F, 48.27F, 57.48F, 68.07F},
    };

    private static int levels;
    private static int width;
    private static int height;

    private static PostEffectProcessor processor;

    /** Owned by us because {@link PostEffectProcessor#parseEffect} takes one and ShaderLoader's is private. */
    private static ProjectionMatrix2 projection;

    /** The radius baked into the built effect; a different one means a rebuild. */
    private static float builtRadius = Float.NaN;

    /** Whether this frame's render state carries our blur marker, waiting for {@link #render}. */
    private static boolean marked;

    /** Set when the effect failed to build, so a broken shader costs one stack trace, not one per frame. */
    private static boolean broken;

    /** Called where the frame's interface rendering starts. */
    public static void beginFrame()
    {
        marked = false;
    }

    /**
     * Mark the blur layer: everything recorded before lands under the blur, the caller's own
     * draws (its dim, its chrome) go into the fresh root layer on top. Does nothing when the
     * effect is broken or the setting is off.
     *
     * <p>A second caller in the same frame TAKES the mark over rather than being turned away.
     * Vanilla allows exactly one blur per frame ("Can only blur once per frame") and would keep
     * the first claimant — which is the dashboard's tint, recorded long before the overlay panel
     * that comes up over it, so the overlay ended up with no glass behind it at all. The topmost
     * claimant is the right owner: the pass blurs everything recorded UNDER the mark, so moving
     * the mark up still covers what the earlier claimant wanted blurred.</p>
     */
    public static void apply(Batcher2D batcher)
    {
        if (broken || !BBSSettings.interfaceBlur.get())
        {
            return;
        }

        if (marked)
        {
            /* Vanilla's "nothing marked yet" sentinel; the field is private, so the constant it
             * compares against is unmapped and cannot be named here. */
            batcher.getContext().state.blurLayer = Integer.MAX_VALUE;
        }

        marked = true;

        batcher.newRootLayer();
        batcher.applyBlur();
    }

    /**
     * The world under a panel that paints its own background over it (morphing, the texture
     * manager). The same mark as {@link #apply}, and an overlay that comes up over the panel
     * later takes it over — one pass, owned by whatever is on top.
     */
    public static void applyUnder(Batcher2D batcher)
    {
        apply(batcher);
    }

    /**
     * {@code GuiRenderer}'s blur slot, reached from the redirect in {@code GuiRendererMixin} when
     * it composites up to the marked layer. The framebuffer holds the world and every layer
     * before the marker at this point, which is exactly the picture to blur.
     *
     * @return false when this frame has no BBS blur, so vanilla's own {@code renderBlur} runs instead.
     */
    public static boolean render()
    {
        if (!marked)
        {
            return false;
        }

        marked = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        float radius = BBSSettings.interfaceBlurRadius.get();

        if (processor == null || radius != builtRadius
            || width != mc.getFramebuffer().textureWidth || height != mc.getFramebuffer().textureHeight)
        {
            if (!rebuild(mc, radius))
            {
                return true;
            }
        }

        /* The frame graph sizes the swap target off the framebuffer and puts the result back into
         * it, so the blend/framebuffer/texture-unit restoration 1.21.1 had to do by hand afterwards
         * has nothing left to undo — a render pass owns its own state now. */
        processor.render(mc.getFramebuffer(), ObjectAllocator.TRIVIAL);

        return true;
    }

    private static boolean rebuild(MinecraftClient mc, float radius)
    {
        close();

        try
        {
            /* The same near/far/invert ShaderLoader builds its own with, so our passes project
             * their screen quad exactly like every vanilla post effect does. */
            projection = new ProjectionMatrix2("bbs_interface_blur", 0.1F, 1000F, false);

            width = mc.getFramebuffer().textureWidth;
            height = mc.getFramebuffer().textureHeight;
            levels = 0;
            int w = width, h = height;
            while (levels < MAX_LEVELS && w / 2 >= MIN_SIZE && h / 2 >= MIN_SIZE)
            {
                w /= 2;
                h /= 2;
                levels++;
            }
            if (levels == 0) return false;

            processor = PostEffectProcessor.parseEffect(pipeline(radius), mc.getTextureManager(),
                Set.of(PostEffectProcessor.MAIN), Identifier.of(BBSMod.MOD_ID, "interface_blur"), projection);

            builtRadius = radius;

            return true;
        }
        catch (Exception e)
        {
            e.printStackTrace();

            close();
            broken = true;

            return false;
        }
    }

    /** The fewest levels that reach {@code target} without pushing the offset past {@link #MAX_CLEAN_OFFSET}. */
    private static int pickLevels(float target)
    {
        for (int n = 1; n < levels; n++)
        {
            if (sigma(n, MAX_CLEAN_OFFSET) >= target)
            {
                return n;
            }
        }

        return levels;
    }

    /** Invert the (piecewise linear) sigma row of {@code n} levels; extrapolates past either end of the table. */
    private static float pickOffset(int n, float target)
    {
        float[] row = SIGMA[n - 1];
        int last = OFFSETS.length - 1;
        int i = 0;

        while (i < last - 1 && row[i + 1] < target)
        {
            i++;
        }

        float t = (target - row[i]) / (row[i + 1] - row[i]);
        float offset = OFFSETS[i] + t * (OFFSETS[i + 1] - OFFSETS[i]);

        return Math.max(MIN_OFFSET, Math.min(OFFSETS[last], offset));
    }

    private static float sigma(int n, float offset)
    {
        float[] row = SIGMA[n - 1];

        for (int i = 0; i < OFFSETS.length - 1; i++)
        {
            if (offset <= OFFSETS[i + 1])
            {
                float t = (offset - OFFSETS[i]) / (OFFSETS[i + 1] - OFFSETS[i]);

                return row[i] + t * (row[i + 1] - row[i]);
            }
        }

        return row[row.length - 1];
    }

    private static PostEffectPipeline pipeline(float radius)
    {
        float target = (float) Math.sqrt(radius * (radius + 1) / 3D);
        int n = pickLevels(target);
        float offset = pickOffset(n, target);
        Map<Identifier, PostEffectPipeline.Targets> targets = new LinkedHashMap<>();
        List<PostEffectPipeline.Pass> passes = new ArrayList<>();
        List<Identifier> chain = new ArrayList<>();
        chain.add(PostEffectProcessor.MAIN);
        int w = width, h = height;

        for (int i = 1; i <= n; i++)
        {
            w /= 2;
            h /= 2;
            Identifier id = Identifier.of(BBSMod.MOD_ID, "kawase_" + i);
            targets.put(id, new PostEffectPipeline.Targets(Optional.of(w), Optional.of(h), false, 0));
            chain.add(id);
            passes.add(pass(chain.get(i - 1), id, DOWN, offset));
        }
        for (int i = n; i > 0; i--)
        {
            passes.add(pass(chain.get(i), chain.get(i - 1), UP, offset));
        }
        return new PostEffectPipeline(targets, passes);
    }

    private static PostEffectPipeline.Pass pass(Identifier in, Identifier out, Identifier shader, float offset)
    {
        return new PostEffectPipeline.Pass(SCREEN_QUAD, shader,
            List.of(new PostEffectPipeline.TargetSampler("In", in, false, true)), out,
            Map.of("BlurConfig", List.of(new UniformValue.FloatValue(offset))));
    }

    private static void close()
    {
        if (processor != null)
        {
            processor.close();
        }

        if (projection != null)
        {
            projection.close();
        }

        processor = null;
        projection = null;
        builtRadius = Float.NaN;
    }
}
