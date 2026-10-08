package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utility.UIUtilityOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_together.TogetherSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIUtilityOverlayPanel.class}, remap=false)
public abstract class UIUtilityOverlayPanelMixin
extends UIOverlayPanel {
    @Shadow(remap=false)
    public UIScrollView view;

    protected UIUtilityOverlayPanelMixin(IKey title) {
        super(title);
    }

    @Inject(method={"<init>"}, at={@At(value="TAIL")}, remap=false)
    private void onInit(IKey title, Runnable callback, CallbackInfo ci) {
        UIToggle syncDownload = new UIToggle(L10n.lang((String)"bbs_together.utility.sync.download"), b -> TogetherSettings.assetSyncDownload.set((Boolean)b.getValue()));
        syncDownload.setValue(((Boolean)TogetherSettings.assetSyncDownload.get()).booleanValue());
        UIToggle syncUpload = new UIToggle(L10n.lang((String)"bbs_together.utility.sync.upload"), b -> TogetherSettings.assetSyncUpload.set((Boolean)b.getValue()));
        syncUpload.setValue(((Boolean)TogetherSettings.assetSyncUpload.get()).booleanValue());
        UIToggle syncReceive = new UIToggle(L10n.lang((String)"bbs_together.utility.sync.receive"), b -> TogetherSettings.assetSyncReceive.set((Boolean)b.getValue()));
        syncReceive.setValue(((Boolean)TogetherSettings.assetSyncReceive.get()).booleanValue());
        UIToggle syncProgress = new UIToggle(L10n.lang((String)"bbs_together.utility.sync.progress"), b -> TogetherSettings.assetSyncProgressBar.set((Boolean)b.getValue()));
        syncProgress.setValue(((Boolean)TogetherSettings.assetSyncProgressBar.get()).booleanValue());
        UIToggle syncProgressAlways = new UIToggle(L10n.lang((String)"bbs_together.utility.sync.progress_always"), b -> TogetherSettings.assetSyncProgressBarAlways.set((Boolean)b.getValue()));
        syncProgressAlways.setValue(((Boolean)TogetherSettings.assetSyncProgressBarAlways.get()).booleanValue());
        this.view.add(new IUIElement[]{UI.label((IKey)L10n.lang((String)"bbs_together.utility.sync.title")), UI.column((UIElement[])new UIElement[]{syncDownload, syncUpload, syncReceive, syncProgress, syncProgressAlways}).marginBottom(8)});
    }
}

