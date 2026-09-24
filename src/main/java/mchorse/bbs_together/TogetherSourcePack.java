package mchorse.bbs_together;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;

public class TogetherSourcePack
implements ISourcePack {
    public static final String PREFIX = "bbs_together";
    private static final String INTERNAL_PREFIX = "assets/bbs_together/assets";

    public String getPrefix() {
        return PREFIX;
    }

    public boolean hasAsset(Link link) {
        return this.getClass().getClassLoader().getResource("assets/bbs_together/assets/" + link.path) != null;
    }

    public InputStream getAsset(Link link) throws IOException {
        InputStream stream = this.getClass().getClassLoader().getResourceAsStream("assets/bbs_together/assets/" + link.path);
        if (stream == null) {
            throw new IOException("Not found: " + link.path);
        }
        return stream;
    }

    public File getFile(Link link) {
        return null;
    }

    public Link getLink(File file) {
        return null;
    }

    public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive) {
    }
}

