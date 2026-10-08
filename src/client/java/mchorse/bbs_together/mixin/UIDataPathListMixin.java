package mchorse.bbs_together.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import mchorse.bbs_mod.ui.dashboard.list.UIDataPathList;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.sync.AssetSyncTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(value=EnvType.CLIENT)
@Mixin(value={UIDataPathList.class}, remap=false)
public class UIDataPathListMixin {
    private final Map<UUID, Integer> together_skinCache = new HashMap<UUID, Integer>();
    private int together_lastFilmSelectionsSize = -1;

    @Inject(method={"renderElementPart"}, at={@At(value="TAIL")}, remap=false)
    private void onRenderElementPartTail(UIContext context, DataPath element, int i, int x, int y, boolean hover, boolean selected, CallbackInfo ci) {
        if (!TogetherClientNetwork.isConnected()) {
            return;
        }
        if (element.folder) {
            return;
        }
        if (TogetherClientNetwork.filmSelections.isEmpty()) {
            return;
        }
        UIDataPathList self = (UIDataPathList)(Object)this;
        String filmId = element.toString();
        ArrayList<UUID> playersOnFilm = new ArrayList<UUID>();
        for (Map.Entry<UUID, String> entry : TogetherClientNetwork.filmSelections.entrySet()) {
            if (!filmId.equals(entry.getValue())) continue;
            playersOnFilm.add(entry.getKey());
        }
        if (playersOnFilm.isEmpty()) {
            return;
        }
        ClientPlayNetworkHandler networkHandler = MinecraftClient.getInstance().getNetworkHandler();
        if (networkHandler == null) {
            return;
        }
        int currentSize = TogetherClientNetwork.filmSelections.size();
        if (currentSize != this.together_lastFilmSelectionsSize) {
            this.together_skinCache.clear();
            this.together_lastFilmSelectionsSize = currentSize;
        }
        int iconSize = 10;
        int spacing = 1;
        int totalWidth = playersOnFilm.size() * iconSize + (playersOnFilm.size() - 1) * spacing;
        int baseY = self.area.y + i * self.scroll.scrollItemSize - (int)self.scroll.getScroll();
        int headX = self.area.ex() - totalWidth - 2;
        int offsetX = 0;
        for (UUID uuid : playersOnFilm) {
            int glId = this.together_skinCache.computeIfAbsent(uuid, id -> {
                PlayerListEntry entry = networkHandler.getPlayerListEntry(id);
                if (entry == null) {
                    return -1;
                }
                Identifier skinId = entry.getSkinTexture();
                return MinecraftClient.getInstance().getTextureManager().getTexture(skinId).getGlId();
            });
            if (glId < 0) continue;
            int iconX = headX + offsetX;
            int iconY = baseY + 1;
            Integer playerColor = TogetherClientNetwork.playerColors.get(uuid);
            if (playerColor != null) {
                context.batcher.box((float)(iconX - 1), (float)(iconY - 1), (float)(iconX + iconSize + 1), (float)(iconY + iconSize + 1), playerColor | 0xFF000000);
            }
            context.batcher.texturedBox(glId, -1, (float)iconX, (float)iconY, (float)iconSize, (float)iconSize, 8.0f, 8.0f, 16.0f, 16.0f, 64, 64);
            context.batcher.texturedBox(glId, -1, (float)iconX, (float)iconY, (float)iconSize, (float)iconSize, 40.0f, 8.0f, 48.0f, 16.0f, 64, 64);
            offsetX += iconSize + spacing;
        }

        /* Per-asset sync strip along the bottom edge of the row. The list doesn't know which
         * repository it belongs to, so probe film ids first, then models/particles document keys
         * (with extension-agnostic prefix matching). */
        if (!(Boolean) TogetherSettings.assetSyncProgressBar.get()) return;
        String full = element.toString();
        AssetSyncTracker.AssetState st = AssetSyncTracker.get("film:" + full);
        if (st == null) {
            st = AssetSyncTracker.findByDocumentKey("models/" + full, "particles/" + full);
        }
        if (st != null && st.state != AssetSyncTracker.State.PENDING) {
            float rowBottom = y + self.scroll.scrollItemSize;
            float bx1 = x + 12;
            float bx2 = self.area.ex() - 2;
            int primary = 0xFF000000 | (BBSSettings.primaryColor.get() & 0xFFFFFF);
            if (st.state == AssetSyncTracker.State.ACTIVE) {
                context.batcher.box(bx1, rowBottom - 1F, bx2, rowBottom, 0x33FFFFFF);
                float cycle = (System.currentTimeMillis() % 1000L) / 1000F;
                float prog = cycle < 0.5F ? cycle * 2F : (1F - cycle) * 2F;
                float head = bx1 + (bx2 - bx1 - 6F) * prog;
                context.batcher.box(head, rowBottom - 1F, head + 6F, rowBottom, 0xFFFFFFFF);
            } else if (st.state == AssetSyncTracker.State.DONE) {
                context.batcher.box(bx1, rowBottom - 1F, bx2, rowBottom, primary);
            } else if (st.state == AssetSyncTracker.State.ERROR) {
                context.batcher.box(bx1, rowBottom - 1F, bx2, rowBottom, 0xFFFF5555);
            }
        }
    }
}

