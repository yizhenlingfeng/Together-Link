package mchorse.bbs_together.mixin;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * BBS keeps the namespaced unknown keys of a {@code ValueGroup} in a private map.
 * That map is the only way to hang data on a clip: the clip's own fields are
 * fixed, and BBS only round-trips foreign keys that contain a namespace.
 */
@Mixin(value = ValueGroup.class, remap = false)
public interface ValueGroupForeignAccessor
{
    @Accessor("foreign")
    Map<String, BaseType> bbs_together$getForeign();

    @Accessor("foreign")
    void bbs_together$setForeign(Map<String, BaseType> foreign);
}
