package mchorse.bbs_together;

import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
public interface ITogetherDopeSheet {
    public void together_setExternalHighlights(Map<Integer, Integer> var1);

    public Map<Integer, Integer> together_getExternalHighlights();
}

