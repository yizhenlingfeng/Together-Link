package mchorse.bbs_together;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.ui.UIValueFactory;
import mchorse.bbs_mod.settings.ui.UIValueMap;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.context.UIContextMenu;
import mchorse.bbs_mod.ui.framework.elements.context.UIInterpolationContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.IKeyframeShapeRenderer;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.KeyframeShapeRenderers;
import mchorse.bbs_mod.ui.framework.tooltips.ITooltip;
import mchorse.bbs_mod.ui.framework.tooltips.InterpolationTooltip;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_together.values.ValueKeyframeInterpolation;
import mchorse.bbs_together.values.ValueKeyframeShape;

public class BBSTogetherFix66Client {
    private static boolean registered;

    public static void registerSettingUIs() {
        if (registered) {
            return;
        }
        registered = true;
        UIValueMap.register(ValueKeyframeShape.class, (valueKeyframeShape, uIElement) -> {
            UIIcon uIIcon2 = new UIIcon(() -> BBSTogetherFix66Client.shapeIcon((Integer)valueKeyframeShape.get()), uIIcon -> uIIcon.getContext().replaceContextMenu(contextMenuManager -> {
                KeyframeShape keyframeShape = BBSTogetherFix66Client.shapeAt((Integer)valueKeyframeShape.get());
                for (KeyframeShape keyframeShape2 : KeyframeShape.values()) {
                    IKeyframeShapeRenderer iKeyframeShapeRenderer = (IKeyframeShapeRenderer)KeyframeShapeRenderers.SHAPES.get(keyframeShape2);
                    if (iKeyframeShapeRenderer == null) continue;
                    contextMenuManager.action(iKeyframeShapeRenderer.getIcon(), iKeyframeShapeRenderer.getLabel(), keyframeShape2 == keyframeShape, () -> valueKeyframeShape.set(keyframeShape2.ordinal()));
                }
            }));
            uIIcon2.tooltip(BBSTogetherFix66Client.shapeLabel((Integer)valueKeyframeShape.get()));
            return Arrays.asList(UIValueFactory.column((UIElement)uIIcon2, (BaseValue)valueKeyframeShape));
        });
        UIValueMap.register(ValueKeyframeInterpolation.class, (valueKeyframeInterpolation, uIElement) -> {
            UIIcon uIIcon2 = new UIIcon(() -> BBSTogetherFix66Client.interpolationIcon((Integer)valueKeyframeInterpolation.get()), uIIcon -> {
                Interpolation interpolation = new Interpolation("default_interpolation_picker", Interpolations.MAP, BBSTogetherFix66Client.interpolationAt((Integer)valueKeyframeInterpolation.get()));
                uIIcon.getContext().replaceContextMenu((UIContextMenu)new UIInterpolationContextMenu(interpolation).callback(() -> valueKeyframeInterpolation.set(BBSTogetherFix66Client.interpolationIndex(interpolation.getInterp()))));
            });
            uIIcon2.tooltip((ITooltip)new InterpolationTooltip(0.0f, 0.5f, () -> BBSTogetherFix66Client.interpolationAt((Integer)valueKeyframeInterpolation.get())));
            return Arrays.asList(UIValueFactory.column((UIElement)uIIcon2, (BaseValue)valueKeyframeInterpolation));
        });
    }

    private static KeyframeShape shapeAt(int n) {
        KeyframeShape[] keyframeShapeArray = KeyframeShape.values();
        return n >= 0 && n < keyframeShapeArray.length ? keyframeShapeArray[n] : KeyframeShape.SQUARE;
    }

    private static Icon shapeIcon(int n) {
        IKeyframeShapeRenderer iKeyframeShapeRenderer = (IKeyframeShapeRenderer)KeyframeShapeRenderers.SHAPES.get(BBSTogetherFix66Client.shapeAt(n));
        return iKeyframeShapeRenderer != null ? iKeyframeShapeRenderer.getIcon() : Icons.SHAPES;
    }

    private static IKey shapeLabel(int n) {
        IKeyframeShapeRenderer iKeyframeShapeRenderer = (IKeyframeShapeRenderer)KeyframeShapeRenderers.SHAPES.get(BBSTogetherFix66Client.shapeAt(n));
        return iKeyframeShapeRenderer != null ? iKeyframeShapeRenderer.getLabel() : UIKeys.KEYFRAMES_SHAPES_SQUARE;
    }

    private static List<IInterp> interpolations() {
        return new ArrayList<IInterp>(Interpolations.MAP.values());
    }

    private static IInterp interpolationAt(int n) {
        List<IInterp> list = BBSTogetherFix66Client.interpolations();
        return n >= 0 && n < list.size() ? list.get(n) : Interpolations.LINEAR;
    }

    private static Icon interpolationIcon(int n) {
        return BBSTogetherFix66Client.interpolationIcon(BBSTogetherFix66Client.interpolationAt(n));
    }

    private static Icon interpolationIcon(IInterp iInterp) {
        return UIInterpolationContextMenu.INTERP_ICON_MAP.getOrDefault(iInterp, Icons.INTERP_LINEAR);
    }

    private static int interpolationIndex(IInterp iInterp) {
        List<IInterp> list = BBSTogetherFix66Client.interpolations();
        int n = list.indexOf(iInterp);
        return n < 0 ? 0 : n;
    }
}

