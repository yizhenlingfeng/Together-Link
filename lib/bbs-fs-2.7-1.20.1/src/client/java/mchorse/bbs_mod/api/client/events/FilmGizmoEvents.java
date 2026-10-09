package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import net.minecraft.client.util.math.MatrixStack;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Alternative actor gizmo placement. Called for both visual capture (stencil=null) and
 * picking; use the same placement in both. Return true when handled and balance stack pushes. */
public final class FilmGizmoEvents
{
    public static final Event<Draw> DRAW = EventFactory.createArrayBacked(Draw.class,
        listeners -> (context, stencil, stack) ->
        {
            for (Draw listener : listeners) if (listener.draw(context, stencil, stack)) return true;
            return false;
        });
    public interface Draw { boolean draw(FilmControllerContext context, StencilMap stencil, MatrixStack stack); }
    private FilmGizmoEvents() {}
}
