package mchorse.bbs_together.ui;

import mchorse.bbs_mod.l10n.keys.IKey;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

/**
 * Cooperative recording labels, resolved from Minecraft's own language table
 * ({@code assets/bbs_together/lang/*.json}) instead of BBS's L10n.
 *
 * <p>Why not L10n: BBS's language subsystem and the mod's strings registration had a
 * murky interaction in the field (coop keys kept showing as raw key names), while the
 * vanilla table is deterministic — Fabric merges every mod's {@code assets/<ns>/lang/}
 * files on every resource reload, and {@link Text#translatable} re-resolves on every
 * {@code get()}, so a language switch updates the UI immediately.</p>
 */
@Environment(EnvType.CLIENT)
public final class CoopL10n
{
    private CoopL10n()
    {}

    public static IKey key(String translationKey)
    {
        return new IKey()
        {
            @Override
            public String get()
            {
                return Text.translatable(translationKey).getString();
            }
        };
    }
}
