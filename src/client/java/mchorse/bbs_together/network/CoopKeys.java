package mchorse.bbs_together.network;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.glfw.GLFW;

/**
 * Cooperative recording hotkeys. Registered into BBS's own keybind system (category
 * "film_controller") through {@code RegisterKeybindsEvent}, so they show up — and are
 * rebindable, and persist — in the BBS settings UI next to the editor's own keys.
 *
 * <p>The same combos drive both the BBS-screen popup (drawn over any BBS menu) and the
 * in-world HUD popup: {@link KeyCombo#isHeld()} polls raw GLFW state, so it works with
 * no BBS screen open at all.</p>
 *
 * <p>Normal class on purpose: mixin classes must not carry non-private static fields
 * (project rule), and BBS reads combos reflectively off a plain class.</p>
 */
@Environment(EnvType.CLIENT)
public final class CoopKeys
{
    public static final KeyCombo COOP_ACCEPT = new KeyCombo(
        "coop_accept", mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.key.accept"), GLFW.GLFW_KEY_Y
    ).categoryKey("film_controller");

    public static final KeyCombo COOP_DENY = new KeyCombo(
        "coop_deny", mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.key.deny"), GLFW.GLFW_KEY_N
    ).categoryKey("film_controller");

    private CoopKeys()
    {}
}
