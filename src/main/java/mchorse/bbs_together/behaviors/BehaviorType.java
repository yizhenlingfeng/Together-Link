package mchorse.bbs_together.behaviors;

/**
 * The kind of a server behavior log entry.
 *
 * <p>The ordinal doubles as the wire/storage ID, and the display order
 * {@code DOWNLOAD (D) -> EDIT (E) -> UPLOAD (U)} follows the English initials,
 * the way the behavior sort groups them.</p>
 */
public enum BehaviorType
{
    EDIT(0),
    DOWNLOAD(1),
    UPLOAD(2),
    OTHER(3);

    public final int id;

    BehaviorType(int id)
    {
        this.id = id;
    }

    public static BehaviorType byId(int id)
    {
        BehaviorType[] values = values();

        return id >= 0 && id < values.length ? values[id] : OTHER;
    }

    /* Sub type constants, so entries can say what exactly happened */
    public static final int SUB_EDIT_MODEL = 0;
    public static final int SUB_EDIT_FILM = 1;
    public static final int SUB_EDIT_MODEL_BLOCK = 2;
    public static final int SUB_DOWNLOAD_MODELS = 3;
    public static final int SUB_UPLOAD_MODELS = 4;
    public static final int SUB_OTHER = 5;
    /** A player asked the server (via the model list's context menu) to delete a model. */
    public static final int SUB_REQUEST_MODEL_DELETE = 6;
}
