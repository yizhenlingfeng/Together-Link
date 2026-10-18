package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.bbsop.BbsOpClientState;
import mchorse.bbs_together.bbsop.BbsOpDomain;
import mchorse.bbs_together.network.CoopClient;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.ui.CoopL10n;
import mchorse.bbs_together.ui.UICoopInvitePanel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * The film preview toolbar's two record buttons: greyed out and dead for players without
 * both the film and the record sub-permission (re-evaluated every frame, so a live
 * permission change applies without reopening the panel).
 *
 * <p>The record button's own right-click menu (auto keyframe / instant keyframes / teleport
 * to player) is also where "request cooperative recording" lives: {@link UIIcon}'s context
 * consumers are append-only, so a constructor-tail injection adds the coop actions without
 * touching BBS's own lambdas.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UIFilmPreview.class, remap = false)
public class UIFilmPreviewBbsOpMixin
{
    @Shadow(remap = false)
    public UIIcon recordReplay;

    @Shadow(remap = false)
    public UIIcon recordVideo;

    @Shadow(remap = false)
    public UIFilmPanel panel;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void together$addCoopMenuActions(CallbackInfo ci)
    {
        this.recordReplay.context((menu) ->
        {
            if (!TogetherClientNetwork.isConnected() || this.panel == null || !(this.panel.getData() instanceof Film))
            {
                return;
            }

            Film film = (Film) this.panel.getData();

            if (BbsOpClientState.canInviteCoop() && !CoopClient.isInSession())
            {
                menu.action(Icons.VIDEO_CAMERA, CoopL10n.key("bbs_together.coop.menu.request"), () ->
                {
                    UIContext context = this.panel.getContext();

                    if (context != null)
                    {
                        UIOverlay.addOverlay(context, new UICoopInvitePanel(film.getId()), 260, 240);
                    }
                });
            }

            CoopClient.Room room = CoopClient.room;

            if (room != null && !room.isRecording())
            {
                UUID self = MinecraftClient.getInstance().player == null ? null : MinecraftClient.getInstance().player.getUuid();

                if (self != null && self.equals(room.host))
                {
                    menu.action(Icons.CLOSE, CoopL10n.key("bbs_together.coop.menu.disband"), () -> CoopClient.sendCancel(room.id));
                }
            }
        });
    }

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void together$lockRecordButtons(UIContext context, CallbackInfo ci)
    {
        boolean canRecord = BbsOpClientState.canEdit(BbsOpDomain.FILM.id) && BbsOpClientState.canEdit(BbsOpDomain.RECORD.id);

        this.recordReplay.setEnabled(canRecord);
        this.recordVideo.setEnabled(canRecord);
    }
}
