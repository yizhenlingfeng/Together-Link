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
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={UIKeyframeDopeSheet.class}, remap=false)
public class UIKeyframeDopeSheetMixin
implements ITogetherDopeSheet {
    @Shadow(remap=false)
    private UIKeyframes keyframes;
    @Unique
    private Map<Integer, Integer> together_externalHighlights = Collections.emptyMap();

    @Inject(method={"renderSheetKeyframeShapes"}, at={@At(value="HEAD")}, remap=false)
    private void onRenderSheetKeyframeShapes(UIContext uIContext, BufferBuilder class_2872, Matrix4f matrix4f, Area area, UIKeyframeSheet uIKeyframeSheet, int n, CallbackInfo callbackInfo) {
        if (this.together_externalHighlights.isEmpty() || uIKeyframeSheet == null || uIKeyframeSheet.channel == null) {
            return;
        }
        UIKeyframeDopeSheet uIKeyframeDopeSheet = (UIKeyframeDopeSheet)(Object)this;
        List list = uIKeyframeDopeSheet.getSheets();
        int n2 = list.indexOf(uIKeyframeSheet);
        if (n2 < 0) {
            return;
        }
        List list2 = uIKeyframeSheet.channel.getKeyframes();
        int n3 = n + (int)uIKeyframeDopeSheet.getTrackHeight() / 2;
        for (int i = 0; i < list2.size(); ++i) {
            Integer n4 = this.together_externalHighlights.get(n2 << 16 | i);
            if (n4 == null) continue;
            Keyframe keyframe = (Keyframe)list2.get(i);
            int n5 = this.keyframes.toGraphX((double)keyframe.getTick());
            UIKeyframeDopeSheet.renderShape((Keyframe)keyframe, (UIContext)uIContext, (BufferBuilder)class_2872, (Matrix4f)matrix4f, (int)n5, (int)n3, (int)5, (int)n4);
        }
    }

    @Override
    public void together_setExternalHighlights(Map<Integer, Integer> map) {
        this.together_externalHighlights = map == null || map.isEmpty() ? Collections.emptyMap() : map;
    }

    @Override
    public Map<Integer, Integer> together_getExternalHighlights() {
        return this.together_externalHighlights;
    }
}

