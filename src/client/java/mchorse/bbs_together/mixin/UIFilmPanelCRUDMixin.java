package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIFilmPanel.class}, remap=false)
public class UIFilmPanelCRUDMixin {
    @Inject(method={"dupeData"}, at={@At(value="TAIL")}, remap=false)
    private void onDupeData(String name, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIFilmPanel self = (UIFilmPanel)(Object)this;
        if (self.getData() == null) {
            return;
        }
        TogetherClientNetwork.sendFilmSaveSync(((Film)self.getData()).getId(), (Film)self.getData());
        TogetherClientNetwork.sendFilmListChanged();
    }
}

