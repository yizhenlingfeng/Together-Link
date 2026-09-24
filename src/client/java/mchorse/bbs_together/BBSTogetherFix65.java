package mchorse.bbs_together;

import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_together.TogetherSettings;

public final class BBSTogetherFix65 {
    private static final ThreadLocal<Boolean> APPLY_TO_NEXT_KEYFRAME = ThreadLocal.withInitial(() -> false);

    private BBSTogetherFix65() {
    }

    public static void beginAddKeyframe(UIKeyframeSheet uIKeyframeSheet, float f) {
        boolean bl = false;
        if (uIKeyframeSheet != null && uIKeyframeSheet.channel != null) {
            for (Object e : uIKeyframeSheet.channel.getKeyframes()) {
                Keyframe keyframe;
                if (!(e instanceof Keyframe) || (keyframe = (Keyframe)e).getTick() != f) continue;
                bl = true;
                break;
            }
        }
        APPLY_TO_NEXT_KEYFRAME.set(!bl);
    }

    public static void applyDefaultsToNewKeyframe(Keyframe<?> keyframe) {
        try {
            if (Boolean.TRUE.equals(APPLY_TO_NEXT_KEYFRAME.get())) {
                BBSTogetherFix65.applyDefaults(keyframe);
            }
        }
        finally {
            APPLY_TO_NEXT_KEYFRAME.remove();
        }
    }

    public static void applyDefaults(Keyframe<?> keyframe) {
        if (keyframe == null) {
            return;
        }
        if (TogetherSettings.shouldApplyDefaultKeyframeInterpolation()) {
            keyframe.getInterpolation().setInterp(TogetherSettings.getDefaultKeyframeInterpolation());
        }
        // BBS 2.6 removed Keyframe.setColor; color defaults handled by UI layer
    }
}

