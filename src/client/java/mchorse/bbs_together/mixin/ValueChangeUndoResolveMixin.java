package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_together.UndoPathResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes undo/redo entries resolve values that live below a film's direct children.
 *
 * <p>BBS resolves an entry's path with {@code ValueGroup.findRecursively()}, which returns the
 * first path segment's group whenever that group exists — so an entry recorded for
 * {@code replays/<replay>/keyframes/<channel>} resolves to the {@code replays} group, the
 * path-length check in {@code resolveValue()} fails, and the entry is marked invalid: Ctrl+Z
 * silently does nothing for anything nested inside a film. Walking the path segment by segment
 * returns the value the entry was actually recorded for.</p>
 */
@Mixin(value = ValueChangeUndo.class, remap = false)
public abstract class ValueChangeUndoResolveMixin {
    @Inject(method = "resolveValue", at = @At("HEAD"), cancellable = true)
    private void together$resolveDeepPath(ValueGroup context, CallbackInfoReturnable<BaseValue> cir) {
        ValueChangeUndo self = (ValueChangeUndo) (Object) this;

        if (self.name == null) {
            return;
        }

        BaseValue value = UndoPathResolver.resolve(context, self.name.strings);

        if (value != null && value.getPath().equals(self.name)) {
            cir.setReturnValue(value);
        }
    }
}
