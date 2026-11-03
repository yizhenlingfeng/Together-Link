package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Cooperative recording and lock-picker labels, resolved through BBS' own
 * language table ({@code assets/bbs_together/assets/strings/*.json}).
 *
 * <p>These keys used to live in Minecraft's vanilla table
 * ({@code assets/bbs_together/lang/*.json}) so they stayed deterministic while
 * the addon's strings registration was being sorted out. They now sit in the
 * same BBS L10n table as every other editor string, which means they follow the
 * language picked in BBS' settings instead of the vanilla {@code options.txt}
 * language. Server-sent {@code Text.translatable} messages stay on the vanilla
 * table because the server cannot resolve BBS L10n on the client's behalf.</p>
 */
@Environment(EnvType.CLIENT)
public final class CoopL10n
{
    private CoopL10n()
    {}

    public static IKey key(String translationKey)
    {
        return L10n.lang(translationKey);
    }
}
