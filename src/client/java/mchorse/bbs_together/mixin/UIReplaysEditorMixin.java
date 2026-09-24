package mchorse.bbs_together.mixin;

import java.lang.invoke.StringConcatFactory;
import java.util.ArrayList;
import java.util.List;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_together.BBSTogetherFix22;
import mchorse.bbs_together.BBSTogetherFix45;
import mchorse.bbs_together.ITogetherReplaysEditor;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIReplaysEditor.class}, remap=false)
public class UIReplaysEditorMixin
implements ITogetherReplaysEditor {
    @Shadow(remap=false)
    public Film film;
    @Unique
    private final List<String> together_allSheetIds = new ArrayList<String>();

    @Override
    public List<String> together_getAllSheetIds() {
        return this.together_allSheetIds;
    }

    @ModifyVariable(method={"updateChannelsList"}, at=@At(value="INVOKE", target="Ljava/util/List;removeIf(Ljava/util/function/Predicate;)Z", remap=false), ordinal=0, remap=false)
    private List<UIKeyframeSheet> captureAllSheets(List<UIKeyframeSheet> list) {
        this.together_allSheetIds.clear();
        for (UIKeyframeSheet uIKeyframeSheet : list) {
            this.together_allSheetIds.add(UIReplaysEditorMixin.together_sheetKey(uIKeyframeSheet));
        }
        return list;
    }

    @Inject(method={"setReplay(Lmchorse/bbs_mod/film/replays/Replay;ZLmchorse/bbs_mod/ui/film/replays/UIReplaysEditor$OrbitReaction;)V"}, at={@At(value="TAIL")}, remap=false)
    private void onSetReplay(Replay replay, boolean bl, UIReplaysEditor.OrbitReaction orbitReaction, CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected() || this.film == null || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        int n = BBSTogetherFix45.identityReplayIndex(this.film.replays.getList(), replay);
        TogetherClientNetwork.sendReplaySelection(this.film.getId(), n);
    }

    @Unique
    private static String together_sheetKey(UIKeyframeSheet uIKeyframeSheet) {
        if (uIKeyframeSheet == null) {
            return "";
        }
        String string = "";
        try {
            string = uIKeyframeSheet.title == null ? "" : uIKeyframeSheet.title.get();
        }
        catch (Exception exception) {
            // empty catch block
        }
        return uIKeyframeSheet.id + "\u0002" + string + "\u0002" + uIKeyframeSheet.isBoneTrack;
    }
}

