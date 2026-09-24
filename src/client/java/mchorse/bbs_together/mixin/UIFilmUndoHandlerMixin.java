package mchorse.bbs_together.mixin;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.utils.UIFilmUndoHandler;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_together.BBSTogetherFix22;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmUndoHandler.class}, remap=false)
public class UIFilmUndoHandlerMixin {
    @Shadow
    private Set<BaseValue> syncData;
    @Unique
    private static Field together_uiElementField;
    @Unique
    private final Set<BaseValue> together_syncQueue = new HashSet<BaseValue>();
    @Unique
    private long together_lastFlush;
    @Unique
    private static final long FLUSH_INTERVAL_MS = 100L;

    @Inject(method={"handleValue"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void cancelRemoteHandleValue(BaseValue baseValue, CallbackInfo callbackInfo) {
        if (BBSTogetherFix22.isApplyingRemoteSync()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method={"handleValue"}, at={@At(value="TAIL")}, remap=false)
    private void onHandleValue(BaseValue baseValue, CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected() || BBSTogetherFix22.isApplyingRemoteSync()) {
            return;
        }
        UIFilmPanel uIFilmPanel = this.together_getFilmPanel();
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            return;
        }
        this.together_syncQueue.add(baseValue);
        this.together_lastFlush = 0L;
    }

    @Inject(method={"handleTimers"}, at={@At(value="HEAD")}, cancellable=true, remap=false)
    private void cancelRemoteHandleTimers(CallbackInfo callbackInfo) {
        if (BBSTogetherFix22.isApplyingRemoteSync()) {
            this.together_syncQueue.clear();
            if (this.syncData != null) {
                this.syncData.clear();
            }
            callbackInfo.cancel();
        }
    }

    @Inject(method={"handleTimers"}, at={@At(value="TAIL")}, remap=false)
    private void onHandleTimers(CallbackInfo callbackInfo) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (BBSTogetherFix22.isApplyingRemoteSync()) {
            this.together_syncQueue.clear();
            return;
        }
        if (this.together_syncQueue.isEmpty()) {
            return;
        }
        long l = System.currentTimeMillis();
        if (l - this.together_lastFlush < 100L) {
            return;
        }
        this.together_lastFlush = l;
        UIFilmPanel uIFilmPanel = this.together_getFilmPanel();
        if (uIFilmPanel == null || uIFilmPanel.getData() == null) {
            this.together_syncQueue.clear();
            return;
        }
        String string = ((Film)uIFilmPanel.getData()).getId();
        HashSet<BaseValue> hashSet = new HashSet<BaseValue>(this.together_syncQueue);
        hashSet.removeIf(baseValue -> {
            for (BaseValue baseValue2 = baseValue.getParent(); baseValue2 != null; baseValue2 = baseValue2.getParent()) {
                if (!hashSet.contains(baseValue2)) continue;
                return true;
            }
            return false;
        });
        for (BaseValue baseValue2 : hashSet) {
            TogetherClientNetwork.sendSyncData(string, baseValue2);
        }
        this.together_syncQueue.clear();
    }

    @Unique
    private UIFilmPanel together_getFilmPanel() {
        try {
            Field field = UIFilmUndoHandlerMixin.together_getUiElementField();
            if (field == null) {
                return null;
            }
            Object object = field.get(this);
            if (object instanceof UIFilmPanel) {
                UIFilmPanel uIFilmPanel = (UIFilmPanel)object;
                return uIFilmPanel;
            }
            if (object instanceof UIClipsPanel) {
                UIClipsPanel uIClipsPanel = (UIClipsPanel)object;
                return uIClipsPanel.filmPanel;
            }
            return null;
        }
        catch (IllegalAccessException illegalAccessException) {
            return null;
        }
    }

    @Unique
    private static Field together_getUiElementField() {
        if (together_uiElementField != null) {
            return together_uiElementField;
        }
        try {
            Field field = UIFormUndoHandler.class.getDeclaredField("uiElement");
            field.setAccessible(true);
            together_uiElementField = field;
            return field;
        }
        catch (NoSuchFieldException noSuchFieldException) {
            System.err.println("[BBS Together] UIFilmUndoHandlerMixin: could not find UIFormUndoHandler#uiElement via reflection: " + noSuchFieldException.getMessage());
            return null;
        }
    }
}

