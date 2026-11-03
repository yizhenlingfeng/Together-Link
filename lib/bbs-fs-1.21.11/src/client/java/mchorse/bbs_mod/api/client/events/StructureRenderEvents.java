package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Draw an alternative representation after the source is loaded. First listener returning
 * true owns the draw. Called for picking too; render parts do not recursively fire this event. */
public final class StructureRenderEvents
{
    public static final Event<Render> RENDER = EventFactory.createArrayBacked(Render.class,
        listeners -> (renderer, form, data, context) ->
        {
            for (Render listener : listeners) if (listener.render(renderer, form, data, context)) return true;
            return false;
        });
    public interface Render
    {
        boolean render(StructureFormRenderer renderer, StructureForm form, StructureRenderData data, FormRenderingContext context);
    }
    private StructureRenderEvents() {}
}
