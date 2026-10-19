package mchorse.bbs_together.locks;

/**
 * The kinds of asset the level-2 "lock asset" feature can protect.
 *
 * <p>Each type uses its own id space on the wire and in persistence:
 * a model uses its model id ({@code models/<id>/...}), a film its film id,
 * and a model block the dimension plus block coordinates.</p>
 */
public enum AssetLockType
{
    MODEL("model"),
    FILM("film"),
    MODEL_BLOCK("model_block");

    public final String id;

    AssetLockType(String id)
    {
        this.id = id;
    }

    public static AssetLockType byId(String id)
    {
        if (id != null)
        {
            for (AssetLockType type : values())
            {
                if (type.id.equals(id))
                {
                    return type;
                }
            }
        }

        return null;
    }
}
