package mchorse.bbs_together.mixin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.items.UIItemGrid;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.tooltips.TooltipPlacement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = UIItemGrid.class, remap = false)
public abstract class UIFormCategoryModelSizeMixin
{
    @Shadow protected int hoverIndex;
    @Shadow private String hoverCaption;

    @Unique private static final Area together_CARD = new Area();
    @Unique private final Map<String, String> together_sizeCache = new HashMap<>();
    @Unique private Method together_visibleMethod;

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true, remap = false)
    private void together$renderModelSizeTooltip(UIContext context, Area area, CallbackInfo ci)
    {
        if (this.hoverIndex == -1 || this.hoverCaption == null) return;

        Object item = together$getHoveredItem();
        if (!(item instanceof Form form)) return;

        String name = this.hoverCaption;
        String size = form instanceof ModelForm modelForm ? together$modelSize(modelForm) : null;

        FontRenderer font = context.batcher.getFont();
        int extraH = size == null ? 0 : font.getHeight() + 4;
        int cardW = Math.max(font.getWidth(name), size == null ? 0 : font.getWidth(size)) + 6;
        int cardH = font.getHeight() + 6 + extraH;

        TooltipPlacement.nearMouse(context, cardW, cardH, 8, true, 2, together_CARD);
        context.batcher.textCard(name, together_CARD.x + 3, together_CARD.y + 3, Colors.WHITE, Colors.A75, 3);
        if (size != null)
        {
            context.batcher.textCard(size, together_CARD.x + 3, together_CARD.y + 3 + font.getHeight() + 4, Colors.WHITE, Colors.A75, 3);
        }
        ci.cancel();
    }

    @Unique
    @SuppressWarnings("unchecked")
    private Object together$getHoveredItem()
    {
        try
        {
            if (this.together_visibleMethod == null)
            {
                Method m = null;
                Class<?> cls = this.getClass();
                while (cls != null && m == null)
                {
                    try { m = cls.getDeclaredMethod("visible"); }
                    catch (NoSuchMethodException e) { cls = cls.getSuperclass(); }
                }
                if (m == null) return null;
                m.setAccessible(true);
                this.together_visibleMethod = m;
            }
            List list = (List) this.together_visibleMethod.invoke(this);
            if (this.hoverIndex >= 0 && this.hoverIndex < list.size()) return list.get(this.hoverIndex);
        }
        catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private String together$modelSize(ModelForm form)
    {
        String id = form.model.get();
        if (id == null || id.isEmpty()) return null;

        String cached = this.together_sizeCache.get(id);
        if (cached != null) return cached.isEmpty() ? null : cached;

        File folder = new File(BBSMod.getAssetsFolder(), "models/" + id);
        String result = null;
        if (folder.exists() && folder.isDirectory())
        {
            long bytes = together$folderSize(folder);
            if (bytes > 0) result = together$formatSize(bytes);
        }
        this.together_sizeCache.put(id, result == null ? "" : result);
        return result;
    }

    @Unique
    private long together$folderSize(File dir)
    {
        long length = 0;
        File[] files = dir.listFiles();
        if (files != null)
        {
            for (File f : files)
            {
                length += f.isDirectory() ? together$folderSize(f) : f.length();
            }
        }
        return length;
    }

    @Unique
    private String together$formatSize(long bytes)
    {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1048576L) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
