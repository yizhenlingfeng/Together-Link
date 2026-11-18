package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.sections.UserFormSection;
import mchorse.bbs_together.sync.FormCategorySync;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * Puts the user form categories where the asset sync can see them.
 *
 * <p>Every read and write of a category goes through
 * {@code UserFormSection.getUserCategoriesFile(int)}, so redirecting that one method moves the
 * whole category store into {@code config/bbs/assets/form_categories} without touching any other
 * BBS code, and the existing asset sync then reports and transfers it like any other asset.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = UserFormSection.class, remap = false)
public abstract class UserFormSectionCategoriesPathMixin
{
    @Inject(method = "getUserCategoriesFile", at = @At("HEAD"), cancellable = true)
    private static void together$putCategoriesInAssets(int index, CallbackInfoReturnable<File> info)
    {
        try
        {
            FormCategorySync.ensureMigrated();

            File file = FormCategorySync.file(index);

            if (file != null)
            {
                info.setReturnValue(file);
            }
        }
        catch (Throwable e)
        {
            /* A broken shared folder must never take the palette down with it: falling through
             * here leaves BBS reading its own config/bbs/settings/forms, which still works. */
            System.out.println("[BBS Assets] Could not use the shared form category folder: " + e);
        }
    }
}
