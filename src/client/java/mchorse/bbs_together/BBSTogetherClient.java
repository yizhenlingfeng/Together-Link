package mchorse.bbs_together;

import java.util.ArrayList;
import java.util.List;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.client.events.RegisterDashboardPanelsEvent;
import mchorse.bbs_mod.api.client.events.RegisterL10nEvent;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.ui.UIServerBehaviorsPanel;
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

    @Subscribe
    public void onRegisterDashboardPanels(RegisterDashboardPanelsEvent event) {
        /* Lands after BBS's own panels (last: the audio editor), per the plan */
        event.dashboard.getPanels().registerPanel(new UIServerBehaviorsPanel(event.dashboard), mchorse.bbs_mod.l10n.L10n.lang("bbs_together.behaviors.title"), Icons.SERVER);
    }
}
