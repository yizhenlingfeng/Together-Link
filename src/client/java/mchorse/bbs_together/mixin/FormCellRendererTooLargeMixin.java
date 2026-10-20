package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.forms.forms.VideoForm;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.forms.FormCellRenderer;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.locks.AssetLockClient;
import mchorse.bbs_together.locks.AssetLockType;
import mchorse.bbs_together.network.TogetherClientNetwork;
import mchorse.bbs_together.sync.OversizedAssets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a morph cell whose asset is too big to sync: red tint plus a "too large" label.
 *
 * <p>Models are labelled by aggregated model size (a model is many files, none of which may be
 * over the limit on its own); videos and particles are labelled by the single over-size file
 * behind them, the same registry the lists and the texture grid use.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(value = FormCellRenderer.class, remap = false)
public abstract class FormCellRendererTooLargeMixin
{
    private static final int TOP_RED = 0xCC_8B0000;
    private static final int BOT_RED = 0xCC_D02020;
    private static final int TOP_LOCKED = 0xCC_5A5A5A;
    private static final int BOT_LOCKED = 0xCC_3A3A3A;

    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private static void together$paintTooLarge(UIContext context, Form form, int x, int y, int w, int h, CellState state, CallbackInfo ci)
    {
        if (together$paintLocked(context, form, x, y, w, h))
        {
            return;
        }

        String label = together$label(form);

        if (label != null)
        {
            context.batcher.gradientVBox(x, y, x + w, y + h, TOP_RED, BOT_RED);

            FontRenderer font = context.batcher.getFont();
            int tx = x + (w - font.getWidth(label)) / 2;
            int ty = y + (h - font.getHeight()) / 2;

            context.batcher.text(label, tx, ty, 0xFFFFFFFF, true);
        }

        together$paintOwnLock(context, form, x, y, w, h);
    }

    /**
     * The lock owner gets a small padlock in the cell's bottom-right corner so they can
     * recognise the models they locked. The big grey overlay is for players the lock
     * actually denies, in which case {@link #together$paintLocked} already returned.
     */
    private static void together$paintOwnLock(UIContext context, Form form, int x, int y, int w, int h)
    {
        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        String id = modelForm.model.get();

        if (id == null || !AssetLockClient.isOwnLock(AssetLockType.MODEL, id))
        {
            return;
        }

        context.batcher.outlinedIcon(Icons.LOCKED, x + w - 9, y + h - 9, 0.5F, 0.5F);
    }

    /** Locked models get the same treatment as over-size ones, in grey, but only when this player cannot edit them. */
    private static boolean together$paintLocked(UIContext context, Form form, int x, int y, int w, int h)
    {
        if (!(form instanceof ModelForm modelForm))
        {
            return false;
        }

        String id = modelForm.model.get();

        if (id == null || !AssetLockClient.isLockedForSelf(AssetLockType.MODEL, id))
        {
            return false;
        }

        context.batcher.gradientVBox(x, y, x + w, y + h, TOP_LOCKED, BOT_LOCKED);
        context.batcher.outlinedIcon(Icons.LOCKED, x + w / 2, y + h / 2 - 7, 0.5F, 0.5F);

        String label = L10n.lang("bbs_together.locks.badge.locked").get();
        FontRenderer font = context.batcher.getFont();
        int tx = x + (w - font.getWidth(label)) / 2;
        int ty = y + (h - font.getHeight()) / 2 + 2;

        context.batcher.text(label, tx, ty, 0xFFFFFFFF, true);

        return true;
    }

    private static String together$label(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            String id = modelForm.model.get();

            return id != null && TogetherClientNetwork.tooLargeModels.contains(id) ? "模型过大" : null;
        }

        if (OversizedAssets.isEmpty())
        {
            return null;
        }

        Link link = null;

        if (form instanceof VideoForm videoForm)
        {
            link = videoForm.video.get();
        }
        else if (form instanceof ParticleForm particleForm)
        {
            String effect = particleForm.effect.get();

            if (effect != null && !effect.isEmpty() && OversizedAssets.sizeOf("particles/" + effect + ".json") != null)
            {
                return "文件过大";
            }

            link = particleForm.texture.get();
        }

        if (link == null)
        {
            return null;
        }

        return OversizedAssets.sizeOf(link.toString()) != null ? "文件过大" : null;
    }
}
