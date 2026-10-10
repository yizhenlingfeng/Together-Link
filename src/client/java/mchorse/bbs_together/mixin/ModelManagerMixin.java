package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_together.behaviors.BehaviorType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.utils.ModelPathUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * The model editor saves the model client-side only, so the save is reported
 * to the server (as an audit hint) — but only when the file actually changed:
 * the serialized file is parsed before and after the save, and a save whose
 * result is semantically identical (a no-op save, or an undone edit) is not
 * reported at all.
 */
@Mixin(ModelManager.class)
public class ModelManagerMixin
{
    /** The file as it stood before the save; null when it didn't exist or couldn't be parsed. */
    private static final ThreadLocal<MapType> together$beforeSave = new ThreadLocal<>();

    @Inject(method = "saveModel", at = @At("HEAD"))
    private void together$captureBeforeSave(ModelInstance instance, List<String[]> renames, CallbackInfoReturnable<Boolean> cir)
    {
        together$beforeSave.remove();

        try
        {
            File file = this.together$modelFile(instance);

            if (file != null && file.isFile())
            {
                BaseType data = DataToString.read(file);

                if (data != null && data.isMap())
                {
                    together$beforeSave.set(data.asMap());
                }
            }
        }
        catch (Exception ignored)
        {}
    }

    @Inject(method = "saveModel", at = @At("RETURN"))
    private void together$reportModelSave(ModelInstance instance, List<String[]> renames, CallbackInfoReturnable<Boolean> cir)
    {
        if (!cir.getReturnValueZ() || instance == null)
        {
            together$beforeSave.remove();

            return;
        }

        MapType before = together$beforeSave.get();

        together$beforeSave.remove();

        try
        {
            Link link = instance.getModelFile();
            File file = link == null ? null : ((ModelManager) (Object) this).provider.getFile(link);

            if (file == null || !file.isFile())
            {
                return;
            }

            /* Content comparison: only a save that actually changed the file gets logged.
             * A missing "before" (new file, unreadable before) counts as changed. */
            if (before != null)
            {
                BaseType after = DataToString.read(file);

                if (after != null && after.isMap() && BaseType.equals(before, after.asMap()))
                {
                    return;
                }
            }

            String modelId = ModelPathUtils.extractModelId(link.path);

            if (modelId == null || modelId.isEmpty())
            {
                return;
            }

            TogetherClientNetwork.sendBehaviorReport(BehaviorType.SUB_EDIT_MODEL, modelId, Collections.singletonList(modelId));
        }
        catch (Exception ignored)
        {}
    }

    /** The file this model would be saved into. */
    private File together$modelFile(ModelInstance instance)
    {
        if (instance == null)
        {
            return null;
        }

        Link link = instance.getModelFile();

        return link == null ? null : ((ModelManager) (Object) this).provider.getFile(link);
    }
}
