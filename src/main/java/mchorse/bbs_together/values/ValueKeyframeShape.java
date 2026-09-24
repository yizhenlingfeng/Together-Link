package mchorse.bbs_together.values;

import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;

public class ValueKeyframeShape
extends ValueInt {
    public ValueKeyframeShape(String string, KeyframeShape keyframeShape) {
        super(string, Integer.valueOf(keyframeShape.ordinal()), Integer.valueOf(0), Integer.valueOf(KeyframeShape.values().length - 1));
    }
}

