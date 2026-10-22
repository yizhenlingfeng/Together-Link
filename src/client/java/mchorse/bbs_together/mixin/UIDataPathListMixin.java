package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.ui.dashboard.list.UIDataPathList;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.ui.IFilmListMarker;
import mchorse.bbs_together.ui.UIPlayerFaceRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIDataPathList.class}, remap=false)
public class UIDataPathListMixin implements IFilmListMarker {
    @Unique
    private boolean bbs$filmList;

    @Override
    public boolean bbs$isFilmList() {
        return this.bbs$filmList;
    }

    @Override
    public void bbs$setFilmList(boolean filmList) {
        this.bbs$filmList = filmList;
    }

    @Inject(method={"renderElementPart"}, at={@At(value="TAIL")}, remap=false)
    private void onRenderElementPartTail(UIContext context, DataPath element, int i, int x, int y, boolean hover, boolean selected, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        UIDataPathList self = (UIDataPathList)(Object)this;
        if (!element.folder && this.bbs$isFilmList()) {
            String path = element.toString();
            if (AssetLockClient.get(AssetLockType.FILM, path) != null) {
                boolean locked = AssetLockClient.isLockedForSelf(AssetLockType.FILM, path);
                boolean own = AssetLockClient.isOwnLock(AssetLockType.FILM, path);
                boolean manage = AssetLockClient.canManage();
                if (locked || own || manage) {
                    int textX = x + 16;
                    int textW = context.batcher.getFont().getWidth(element.getLast());
                    context.batcher.icon(locked ? Icons.LOCKED : Icons.UNLOCKED, Colors.WHITE, textX + textW + 4, y + self.scroll.scrollItemSize / 2F, 0F, 0.5F);
                }
            }
        }
        if (element.folder) {
            return;
        }
        if (TogetherClientNetwork.filmSelections.isEmpty()) {
            return;
        }
        String filmId = element.toString();
        ArrayList<UUID> playersOnFilm = new ArrayList<UUID>();
        for (Map.Entry<UUID, String> entry : TogetherClientNetwork.filmSelections.entrySet()) {
            if (!filmId.equals(entry.getValue())) continue;
            playersOnFilm.add(entry.getKey());
        }
        if (playersOnFilm.isEmpty()) {
            return;
        }
        int iconSize = 10;
        int spacing = 1;
        int totalWidth = playersOnFilm.size() * iconSize + (playersOnFilm.size() - 1) * spacing;
        int baseY = self.area.y + i * self.scroll.scrollItemSize - (int)self.scroll.getScroll();
        int headX = self.area.ex() - totalWidth - 2;
        int offsetX = 0;
        for (UUID uuid : playersOnFilm) {
            int iconX = headX + offsetX;
            int iconY = baseY + 1;
            Integer playerColor = TogetherClientNetwork.playerColors.get(uuid);
            if (playerColor != null) {
                context.batcher.box((float)(iconX - 1), (float)(iconY - 1), (float)(iconX + iconSize + 1), (float)(iconY + iconSize + 1), playerColor | 0xFF000000);
            }
            /* Shared face renderer: same two layers as before, but it copes with a player whose
             * skin is unavailable — a null identifier used to crash the client here. */
            UIPlayerFaceRenderer.draw(context.batcher, TogetherClientNetwork.playerName(uuid), uuid, iconX, iconY, iconSize);
            offsetX += iconSize + spacing;
        }
    }
}
