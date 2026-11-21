package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_together.UndoPathResolver;
import mchorse.bbs_together.locks.KeyframeLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ctrl+Z writes an entry straight back into the data, right in front of the server guard: a
 * rollback recorded before a lock was created would quietly rewrite the locked keyframes and
 * only be bounced afterwards, leaving the client showing an edit the server refused. Refusing
 * here keeps the history and the data in step.
 *
 * <p>Redo is refused for the same reason: undoing something and then redoing it before the
 * server answer arrives must not slip the locked keyframes back in either.</p>
 */
@Mixin(value = ValueChangeUndo.class, remap = false)
public abstract class ValueChangeUndoLockMixin
{
    @Inject(method = "undo", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedUndo(ValueGroup context, CallbackInfo ci)
    {
        ValueChangeUndo undo = (ValueChangeUndo) (Object) this;

        if (bbs_together$blocked(context, undo, undo.oldValue))
        {
            ci.cancel();
        }
    }

    @Inject(method = "redo", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedRedo(ValueGroup context, CallbackInfo ci)
    {
        ValueChangeUndo undo = (ValueChangeUndo) (Object) this;

        if (bbs_together$blocked(context, undo, undo.newValue))
        {
            ci.cancel();
        }
    }

    private static boolean bbs_together$blocked(ValueGroup context, ValueChangeUndo undo, BaseType value)
    {
        BaseValue target = context == null || undo.name == null ? null : UndoPathResolver.resolve(context, undo.name.strings);

        if (!KeyframeLockClient.undoBlocked(target, value))
        {
            return false;
        }

        KeyframeLockClient.notifyUndoBlocked();

        return true;
    }
}
