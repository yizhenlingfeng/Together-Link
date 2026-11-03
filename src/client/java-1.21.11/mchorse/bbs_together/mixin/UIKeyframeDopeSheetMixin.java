package mchorse.bbs_together.mixin;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.ITogetherDopeSheet;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21.11's dope-sheet geometry is recorded into a deferred {@code GuiQuadMesh} with
 * {@link VertexConsumer}/{@link Matrix3x2fc}, not a {@code BufferBuilder}/{@code Matrix4f}.
 * The extra commit-highlight shapes follow the same path.
 */
@Mixin(value = UIKeyframeDopeSheet.class, remap = false)
public class UIKeyframeDopeSheetMixin implements ITogetherDopeSheet
{
    @Shadow(remap = false)
    private UIKeyframes keyframes;

    @Unique
    private Map<Integer, Integer> together_externalHighlights = Collections.emptyMap();

    @Inject(method = "renderSheetKeyframeShapes", at = @At("HEAD"), remap = false)
    private void onRenderSheetKeyframeShapes(UIContext context, VertexConsumer builder, Matrix3x2fc matrix, Area area,
        UIKeyframeSheet sheet, int y, CallbackInfo callbackInfo)
    {
        if (this.together_externalHighlights.isEmpty() || sheet == null || sheet.channel == null)
        {
            return;
        }

        UIKeyframeDopeSheet dopeSheet = (UIKeyframeDopeSheet) (Object) this;
        List<UIKeyframeSheet> sheets = dopeSheet.getSheets();
        int sheetIndex = sheets.indexOf(sheet);

        if (sheetIndex < 0)
        {
            return;
        }

        List<Keyframe> keyframes = sheet.channel.getKeyframes();
        int centerY = y + (int) dopeSheet.getTrackHeight() / 2;

        for (int i = 0; i < keyframes.size(); i++)
        {
            Integer color = this.together_externalHighlights.get(sheetIndex << 16 | i);

            if (color == null)
            {
                continue;
            }

            Keyframe keyframe = keyframes.get(i);
            int x = this.keyframes.toGraphX((double) keyframe.getTick());

            UIKeyframeDopeSheet.renderShape(keyframe, context, builder, matrix, x, centerY, 5, color);
        }
    }

    @Override
    public void together_setExternalHighlights(Map<Integer, Integer> map)
    {
        this.together_externalHighlights = map == null || map.isEmpty() ? Collections.emptyMap() : map;
    }

    @Override
    public Map<Integer, Integer> together_getExternalHighlights()
    {
        return this.together_externalHighlights;
    }
}
