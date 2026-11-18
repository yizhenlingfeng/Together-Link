package mchorse.bbs_together.ui;

import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_together.network.CoopClient;

/**
 * The one screen the coop recording room lives in: the room panel and nothing else. Opens over
 * whatever the player was doing (world or dashboard), closes itself when the session ends or the
 * player leaves.
 */
public class UICoopRoomMenu extends UIBaseMenu
{
    /* Starting size of the room overlay, in BBS UI units. The panel is laid out relatively, so it
     * grows with the BBS UI scale instead of its fonts getting bigger; the corner grip resizes it,
     * and the size the player picks is remembered per panel class by UIOverlay. */
    private static final int WIDTH = 720;
    private static final int HEIGHT = 440;

    @Override
    public void onOpen(UIBaseMenu oldMenu)
    {
        super.onOpen(oldMenu);

        UICoopRoomPanel panel = new UICoopRoomPanel();

        /* Closing the panel by hand (the X) leaves whatever the player is still part of; the
         * session itself stays on the server until they hand a take in or leave. */
        panel.onClose((e) ->
        {
            CoopClient.dismissRoom();

            this.closeThisMenu();
        });

        int width = Math.min(WIDTH, Math.max(480, this.width - 40));
        int height = Math.min(HEIGHT, Math.max(320, this.height - 40));

        UIOverlay.addOverlay(this.context, panel, width, height);
    }

    @Override
    public void onClose(UIBaseMenu nextMenu)
    {
        super.onClose(nextMenu);

        /* Escape, or any other way out of the screen that doesn't go through the panel's close
         * button, still counts as putting the board away. */
        CoopClient.onRoomScreenClosed();
    }
}
