package mchorse.bbs_together;

import java.io.File;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterSettingsEvent;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_together.TogetherSettings;
import mchorse.bbs_together.TogetherSourcePack;

public class BBSTogether
implements BBSAddonMod {
    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event) {
        File settingsFolder = BBSMod.getSettingsFolder();
        BBSMod.setupConfig((Icon)Icons.GLOBE, (String)"bbs_together", (File)new File(settingsFolder, "bbs_together.json"), TogetherSettings::register);
    }

    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event) {
        event.provider.register((ISourcePack)new TogetherSourcePack());
    }
}

