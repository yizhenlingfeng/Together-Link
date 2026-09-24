package mchorse.bbs_together.values;

import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.interps.Interpolations;

public class ValueKeyframeInterpolation
extends ValueInt {
    public ValueKeyframeInterpolation(String string, int n) {
        super(string, Integer.valueOf(n), Integer.valueOf(0), Integer.valueOf(Math.max(0, Interpolations.MAP.size() - 1)));
    }
}

