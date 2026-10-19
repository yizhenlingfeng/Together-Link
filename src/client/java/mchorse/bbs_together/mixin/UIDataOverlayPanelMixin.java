package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.dashboard.list.UIDataPathList;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UICRUDOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UIDataOverlayPanel;
import mchorse.bbs_mod.ui.utils.context.MenuIcon;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.ui.AssetLockUi;
import mchorse.bbs_together.ui.IFilmListMarker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIDataOverlayPanel.class}, remap=false)
public class UIDataOverlayPanelMixin {
    @Shadow(remap=false)
    protected UIDataDashboardPanel panel;

    @Inject(method={"addNewData(Lmchorse/bbs_mod/ui/framework/UIContext;Ljava/lang/String;Lmchorse/bbs_mod/data/types/MapType;)V"}, at={@At(value="TAIL")}, remap=false)
    private void onAddNewData(UIContext context, String name, MapType data, CallbackInfo ci) {
        this.together_syncNewFilm();
    }

    @Inject(method={"addNewFolder"}, at={@At(value="TAIL")}, remap=false)
    private void onAddNewFolder(String path, CallbackInfo ci) {
        TogetherClientNetwork.sendFilmListChanged();
    }

    @Inject(method={"dupeData"}, at={@At(value="TAIL")}, remap=false)
    private void onDupeData(String name, CallbackInfo ci) {
        this.together_syncNewFilm();
    }

    @Unique
    private void together_syncNewFilm() {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (this.panel == null || this.panel.getData() == null) {
            return;
        }
        ValueGroup valueGroup = this.panel.getData();
        if (valueGroup instanceof Film) {
            Film film = (Film)valueGroup;
            TogetherClientNetwork.sendFilmSaveSync(film.getId(), film);
        }
        TogetherClientNetwork.sendFilmListChanged();
    }

    @Inject(method={"renameData"}, at={@At(value="HEAD")}, remap=false)
    private void onRenameDataHead(String name, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (this.panel == null || this.panel.getData() == null) {
            return;
        }
        String oldId = this.panel.getData().getId();
        TogetherClientNetwork.sendFilmRenamed(oldId, name);
    }

    @Inject(method={"renameFolder"}, at={@At(value="TAIL")}, remap=false)
    private void onRenameFolder(String name, CallbackInfo ci) {
        TogetherClientNetwork.sendFilmListChanged();
    }

    @Inject(method={"removeData"}, at={@At(value="HEAD")}, remap=false)
    private void onRemoveDataHead(CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (this.panel == null || this.panel.getData() == null) {
            return;
        }
        String filmId = this.panel.getData().getId();
        TogetherClientNetwork.sendFilmDeleted(filmId);
    }

    @Inject(method={"removeFolder"}, at={@At(value="TAIL")}, remap=false)
    private void onRemoveFolder(CallbackInfo ci) {
        TogetherClientNetwork.sendFilmListChanged();
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void together$addFilmLock(IKey title, UIDataDashboardPanel panel, Consumer<String> callback, CallbackInfo ci) {
        UIDataOverlayPanel self = (UIDataOverlayPanel) (Object) this;
        UIDataPathList namesList = ((UICRUDOverlayPanel) (Object) this).namesList;

        if (this.panel != null && this.panel.getType() == ContentType.FILMS && namesList instanceof IFilmListMarker marker) {
            marker.bbs$setFilmList(true);
        }

        namesList.context((menu) -> {
            if (!BbsOpClientState.canManage() || this.panel == null || this.panel.getType() != ContentType.FILMS) {
                return;
            }

            DataPath path = namesList.getCurrentFirst();

            if (path == null || path.folder || "..".equals(path.getLast())) {
                return;
            }

            String filmId = path.toString();

            menu.icons.add(new MenuIcon(Icons.LOCKED, AssetLockUi.lockActionLabel(AssetLockType.FILM), MenuVerb.Slot.CLIPBOARD, () -> {
                AssetLockUi.openMenu(self.getContext(), AssetLockType.FILM, filmId);
            }));
        });
    }
}

