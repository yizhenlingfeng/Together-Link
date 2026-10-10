package mchorse.bbs_mod.particles;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.manager.BaseManager;
import mchorse.bbs_mod.utils.manager.storage.JSONLikeStorage;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ParticleManager extends BaseManager<ParticleScheme>
{
    private final Map<String, ParticleScheme> cache = new HashMap<>();

    public ParticleManager(Supplier<File> folder)
    {
        super(folder);

        this.storage = new JSONLikeStorage().json();
    }

    @Override
    public ParticleScheme load(String id)
    {
        ParticleScheme cached = this.cache.get(id);

        if (cached != null)
        {
            return cached;
        }

        ParticleScheme loaded = super.load(id);

        if (loaded != null)
        {
            this.cache.put(id, loaded);
        }

        return loaded;
    }

    @Override
    public boolean save(String id, MapType data)
    {
        boolean result = super.save(id, data);

        if (result)
        {
            this.cache.remove(id);
        }

        return result;
    }

    public void invalidate(String id)
    {
        this.cache.remove(id);
    }

    public void clearCache()
    {
        this.cache.clear();
    }

    @Override
    protected ParticleScheme createData(String id, MapType data)
    {
        ParticleScheme scheme = new ParticleScheme();

        if (data != null)
        {
            try
            {
                ParticleScheme.PARSER.fromData(scheme, data);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            scheme.setup();
        }
        else
        {
            scheme.setup();
        }

        return scheme;
    }
}
