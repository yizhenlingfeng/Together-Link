package mchorse.bbs_together;

import java.util.ArrayList;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_together.values.ValueKeyframeInterpolation;
import mchorse.bbs_together.values.ValueKeyframeShape;

public class TogetherSettings {
    public static ValueString cdnUrl;
    public static ValueString cdnToken;
    public static ValueBoolean assetSyncDownload;
    public static ValueBoolean assetSyncUpload;
    public static ValueBoolean assetSyncReceive;
    public static ValueKeyframeShape defaultKeyframeShape;
    public static ValueBoolean defaultKeyframeInterpolationEnabled;
    public static ValueKeyframeInterpolation defaultKeyframeInterpolation;
    public static ValueBoolean defaultKeyframeColorEnabled;
    public static ValueInt defaultKeyframeColor;

    public static void register(SettingsBuilder settingsBuilder) {
        settingsBuilder.category("cdn");
        cdnUrl = settingsBuilder.getString("url", "");
        cdnToken = settingsBuilder.getString("token", "");
        assetSyncDownload = settingsBuilder.getBoolean("download", true);
        assetSyncUpload = settingsBuilder.getBoolean("upload", true);
        assetSyncReceive = settingsBuilder.getBoolean("receive", false);
        settingsBuilder.category("keyframes");
        defaultKeyframeShape = new ValueKeyframeShape("default_shape", KeyframeShape.SQUARE);
        settingsBuilder.register((BaseValue)defaultKeyframeShape);
        defaultKeyframeInterpolationEnabled = settingsBuilder.getBoolean("default_interpolation_enabled", false);
        defaultKeyframeInterpolation = new ValueKeyframeInterpolation("default_interpolation", 0);
        settingsBuilder.register((BaseValue)defaultKeyframeInterpolation);
        defaultKeyframeColorEnabled = settingsBuilder.getBoolean("default_color_enabled", false);
        defaultKeyframeColor = settingsBuilder.getInt("default_color", -1).color();
    }

    public static KeyframeShape getDefaultKeyframeShape() {
        int n = defaultKeyframeShape == null ? 0 : (Integer)defaultKeyframeShape.get();
        KeyframeShape[] keyframeShapeArray = KeyframeShape.values();
        return n >= 0 && n < keyframeShapeArray.length ? keyframeShapeArray[n] : KeyframeShape.SQUARE;
    }

    public static IInterp getDefaultKeyframeInterpolation() {
        int n = defaultKeyframeInterpolation == null ? 0 : (Integer)defaultKeyframeInterpolation.get();
        ArrayList arrayList = new ArrayList(Interpolations.MAP.values());
        return n >= 0 && n < arrayList.size() ? (IInterp)arrayList.get(n) : Interpolations.LINEAR;
    }

    public static boolean shouldApplyDefaultKeyframeInterpolation() {
        return defaultKeyframeInterpolationEnabled != null && (Boolean)defaultKeyframeInterpolationEnabled.get() != false;
    }

    public static boolean shouldApplyDefaultKeyframeColor() {
        return defaultKeyframeColorEnabled != null && (Boolean)defaultKeyframeColorEnabled.get() != false;
    }

    public static int getDefaultKeyframeColor() {
        return defaultKeyframeColor == null ? -1 : (Integer)defaultKeyframeColor.get();
    }
}

