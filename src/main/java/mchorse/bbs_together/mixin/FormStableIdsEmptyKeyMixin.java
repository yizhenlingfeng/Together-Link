package mchorse.bbs_together.mixin;

import java.util.Map;

import mchorse.bbs_mod.data.migration.FormStableIds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Silence BBS's "Track key "" points at body part []" warning spam. Films containing an
 * empty-string track key re-warn on every load (film inventory scans, manager saves,
 * player joins). An empty key can never be rewritten anyway (StringUtils.isInteger("")
 * vacuously returns true, so the key walks into the mapping lookup and fails), and BBS's
 * own fallback for unresolvable keys is returning it unchanged — so returning the key
 * immediately is behavior-identical, just without the warning.
 */
@Mixin(value = {FormStableIds.class}, remap = false)
public class FormStableIdsEmptyKeyMixin {
    @Inject(method = {"rewriteTrackKey"}, at = {@At("HEAD")}, cancellable = true, remap = false)
    private static void together_skipEmptyKey(String key, Map<String, String> mapping, CallbackInfoReturnable<String> cir) {
        if (key == null || key.isEmpty()) {
            cir.setReturnValue(key);
        }
    }
}
