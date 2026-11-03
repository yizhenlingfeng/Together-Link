package mchorse.bbs_together.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Registers the byte payload codec for every channel. Both directions are
 * registered because the client encodes C2S packets and the server encodes S2C
 * packets; the set keeps integrated-server registration idempotent.
 */
public final class TogetherPayloads
{
    private static final Set<Identifier> REGISTERED = new HashSet<>();

    private TogetherPayloads()
    {}

    public static CustomPayload.Id<TogetherPayload> idFor(Identifier id)
    {
        return new CustomPayload.Id<>(id);
    }

    public static void ensureRegistered(Identifier id)
    {
        synchronized (REGISTERED)
        {
            if (!REGISTERED.add(id))
            {
                return;
            }

            CustomPayload.Id<TogetherPayload> payloadId = idFor(id);

            PayloadTypeRegistry.playC2S().register(payloadId, TogetherPayload.codecFor(payloadId));
            PayloadTypeRegistry.playS2C().register(payloadId, TogetherPayload.codecFor(payloadId));
        }
    }

    /**
     * Registers every static {@link Identifier} declared by the channel holder.
     * This runs once from both setup methods, so a channel that is only ever
     * sent (and therefore never registered by a receiver) still has a codec on
     * the sending side. The lazy calls in the networking facades stay in place
     * for any channel declared outside the holder.
     */
    public static void registerAll(Class<?> channelHolder)
    {
        for (Field field : channelHolder.getDeclaredFields())
        {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Identifier.class)
            {
                continue;
            }

            try
            {
                ensureRegistered((Identifier) field.get(null));
            }
            catch (IllegalAccessException ignored)
            {}
        }
    }
}
