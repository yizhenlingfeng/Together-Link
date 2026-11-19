package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_together.BBSTogetherFix67UndoPath;
import mchorse.bbs_together.locks.KeyframeLockClient;
import mchorse.bbs_together.locks.KeyframeLockData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ctrl+Z writes an entry straight back into the data, right in front of the server guard: a
 * rollback recorded before a lock was created would quietly rewrite the locked keyframes and
 * only be bounced afterwards, leaving the client showing an edit the server refused. Refusing
 * here keeps the history and the data in step.
 */
@Mixin(value = ValueChangeUndo.class, remap = false)
public abstract class ValueChangeUndoLockMixin
{
    @Inject(method = "undo", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs_together$blockLockedUndo(ValueGroup context, CallbackInfo ci)
    {
        ValueChangeUndo undo = (ValueChangeUndo) (Object) this;
        BaseValue value = context == null || undo.name == null ? null : BBSTogetherFix67UndoPath.resolve(context, undo.name.strings);
        ValueGroup owner = value == null ? null : KeyframeLockData.ownerOf(value);

        if (owner != null && KeyframeLockClient.undoBlocked(owner, undo.oldValue))
        {
            KeyframeLockClient.notifyUndoBlocked();

            ci.cancel();
        }
    }
}
