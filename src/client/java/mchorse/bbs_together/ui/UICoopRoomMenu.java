package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;

/**
 * The one screen the coop recording room lives in: the picking-room panel and
 * nothing else. Opens over whatever the player was doing (world or dashboard),
 * closes itself when the session ends or the player leaves.
 */
public class UICoopRoomMenu extends UIBaseMenu
{
    private static final int WIDTH = 560;
    private static final int HEIGHT = 400;

    @Override
    public void onOpen(UIBaseMenu oldMenu)
    {
        super.onOpen(oldMenu);

        UICoopRoomPanel panel = new UICoopRoomPanel();

        /* Closing the panel by hand (the X button) must also tell the server — the leave
         * button path already nulls the room before closing, so this only fires for the
         * X and for panel closes that skip the button. */
        panel.onClose((e) ->
        {
            mchorse.bbs_together.network.CoopClient.Room room = mchorse.bbs_together.network.CoopClient.room;

            if (room != null)
            {
                mchorse.bbs_together.network.CoopClient.sendLeave(room.id);
                mchorse.bbs_together.network.CoopClient.room = null;
            }

            this.closeThisMenu();
        });

        UIOverlay.addOverlay(this.context, panel, WIDTH, HEIGHT);
    }
}
