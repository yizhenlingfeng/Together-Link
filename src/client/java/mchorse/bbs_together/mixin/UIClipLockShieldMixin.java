package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_together.locks.ClipLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Greys the panel of a clip this player may not edit. BBS' widgets draw the padlock
 * overlay on their own when they are disabled ({@code UIElement.renderLockedArea}),
 * so the keyframe "Edit" button becomes the locked button of the clip lock, and
 * nothing in the panel can be clicked any more.
 */
@Mixin(value = UIClip.class, remap = false)
public class UIClipLockShieldMixin
{
    /**
     * Elements this mixin disabled, mapped to whether they were enabled beforehand, so a
     * refresh restores exactly what was taken away instead of re-enabling widgets the
     * panel itself wanted disabled.
     */
    private static final Map<UIClip<?>, Map<UIElement, Boolean>> DISABLED = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "fillData", at = @At("TAIL"), remap = false)
    private void bbs_together$shieldLockedClip(CallbackInfo ci)
    {
        shield((UIClip<?>) (Object) this);
    }

    private static void shield(UIClip<?> panel)
    {
        Map<UIElement, Boolean> previous = DISABLED.remove(panel);

        if (previous != null)
        {
            for (Map.Entry<UIElement, Boolean> entry : previous.entrySet())
            {
                if (entry.getValue())
                {
                    entry.getKey().setEnabled(true);
                }
            }
        }

        if (panel.clip == null || ClipLockClient.canEdit(panel.clip))
        {
            return;
        }

        Map<UIElement, Boolean> disabled = new LinkedHashMap<>();

        for (IUIElement child : panel.getChildren())
        {
            collect(child, disabled);
        }

        DISABLED.put(panel, disabled);

        for (UIElement element : disabled.keySet())
        {
            element.setEnabled(false);
        }
    }

    private static void collect(IUIElement child, Map<UIElement, Boolean> out)
    {
        if (!(child instanceof UIElement element))
        {
            return;
        }

        List<IUIElement> children = element.getChildren();

        if (children.isEmpty())
        {
            out.put(element, element.isEnabled());

            return;
        }

        for (IUIElement grandChild : children)
        {
            collect(grandChild, out);
        }
    }
}
