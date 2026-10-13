package mchorse.bbs_together.bbsop;

/**
 * The editable feature domains a BBS permission level can control. Level 2 (admin)
 * may edit everything; level 1 (editor) gets every domain unless the admin has
 * configured an explicit whitelist for them (see {@link BbsOpManager}); level 0
 * (viewer) gets none of them.
 */
public enum BbsOpDomain
{
    MORPH("morph"),
    FILM("film"),
    /**
     * Sub-permission of {@link #FILM}: starting a recording take. Never shown as a
     * top-level entry in the domain menu — the UI surfaces it as a hover popup on the
     * film row ({@code UIDomainMenuWithRecord}), but on the wire, in grants and in
     * persistence it is a plain domain id like any other.
     */
    RECORD("record"),
    MODEL_BLOCKS("model_blocks"),
    PARTICLES("particles"),
    MODEL_EDITOR("model_editor"),
    TEXTURES("textures"),
    AUDIO("audio"),
    UPLOAD("upload");

    /** Stable id used on the wire, in persistence and in i18n keys. */
    public final String id;

    BbsOpDomain(String id)
    {
        this.id = id;
    }

    /** Resolves a wire/persistence id back to the domain; unknown ids yield {@code null}. */
    public static BbsOpDomain byId(String id)
    {
        if (id != null)
        {
            for (BbsOpDomain domain : values())
            {
                if (domain.id.equals(id))
                {
                    return domain;
                }
            }
        }

        return null;
    }
}
