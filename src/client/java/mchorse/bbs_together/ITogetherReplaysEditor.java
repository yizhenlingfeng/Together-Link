package mchorse.bbs_together;

import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
public interface ITogetherReplaysEditor {
    public List<String> together_getAllSheetIds();
}

