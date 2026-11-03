package mchorse.bbs_together;

import mchorse.bbs_together.render.ModelBlockLockRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/**
 * Extra 1.21.11-only client entrypoint. The common entrypoint keeps the shared HUD and
 * network setup; this one registers render hooks whose API only exists on 1.21.11.
 */
@Environment(EnvType.CLIENT)
public class Together12111Client implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        WorldRenderEvents.AFTER_ENTITIES.register(ModelBlockLockRenderer::render);
    }
}
