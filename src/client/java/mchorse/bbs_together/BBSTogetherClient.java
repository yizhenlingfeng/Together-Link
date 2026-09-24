package mchorse.bbs_together;

import java.util.ArrayList;
import java.util.List;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
import mchorse.bbs_mod.resources.Link;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
public class BBSTogetherClient
implements BBSAddonMod {
    @Subscribe
    public void onRegisterL10n(RegisterL10nEvent event) {
        event.l10n.register((lang) -> {
            List<Link> links = new ArrayList<Link>();
            links.add(new Link("bbs_together", "strings/" + lang + ".json"));
            return links;
        });
    }
}
