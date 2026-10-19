package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Multi-select player picker used by the asset-lock menus and by the model block
 * lock section. It follows the cooperative-recording invite panel: an avatar and
 * name per row, click to toggle, then save.
 *
 * <p>Rows come from the online player list, every player the server has told us
 * about (behavior log / BBS level list) and the current lock members, so a lock
 * can keep a player who is offline and a player just joining still appears.</p>
 */
@Environment(EnvType.CLIENT)
public class UIAssetLockPlayersPanel extends UIOverlayPanel
{
    private static final int ROW = 22;

    private final Map<UUID, String> known = new LinkedHashMap<>();
    private final Set<UUID> selected = new LinkedHashSet<>();
    private final PlayerList list;
    private final Consumer<Map<UUID, String>> callback;

    public UIAssetLockPlayersPanel(IKey title, Map<UUID, String> initial, Consumer<Map<UUID, String>> callback)
    {
        super(title);

        this.callback = callback;

        if (initial != null)
        {
            for (Map.Entry<UUID, String> entry : initial.entrySet())
            {
                this.known.put(entry.getKey(), entry.getValue());
                this.selected.add(entry.getKey());
            }
        }

        this.list = new PlayerList();
        this.list.relative(this.content).xy(0, 0).w(1F).h(1F, -26);

        UIButton save = new UIButton(CoopL10n.key("bbs_together.locks.picker.save"), (b) -> this.save());
        UIElement buttons = UI.row(4, save, this.createCancelButton());

        buttons.relative(this.content).x(0).y(1F, -24).w(1F).h(22);

        this.content.add(this.list, buttons);
        this.list.refresh();
    }

    @Override
    public int getPreferredWidth()
    {
        return 320;
    }

    private UIButton createCancelButton()
    {
        return new UIButton(CoopL10n.key("bbs_together.locks.picker.cancel"), (b) -> this.close());
    }

    private void save()
    {
        Map<UUID, String> result = new LinkedHashMap<>();

        for (UUID id : this.selected)
        {
            result.put(id, this.known.getOrDefault(id, ""));
        }

        this.callback.accept(result);
        this.close();
    }

    private class PlayerList extends UIScrollView
    {
        PlayerList()
        {
            super();

            this.column(0).vertical().stretch().scroll();
        }

        void refresh()
        {
            this.removeAll();

            MinecraftClient mc = MinecraftClient.getInstance();
            UUID self = mc.player == null ? null : mc.player.getUuid();

            if (mc.getNetworkHandler() != null)
            {
                for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList())
                {
                    UUID id = entry.getProfile() == null ? null : entry.getProfile().getId();
                    String name = entry.getProfile() == null ? null : entry.getProfile().getName();

                    if (id != null && name != null && !name.isEmpty())
                    {
                        UIAssetLockPlayersPanel.this.known.put(id, name);
                    }
                }
            }

            for (PlayerRecord record : TogetherClientNetwork.behaviorPlayers.values())
            {
                if (record.id != null && record.name != null && !record.name.isEmpty())
                {
                    UIAssetLockPlayersPanel.this.known.put(record.id, record.name);
                }
            }

            List<Map.Entry<UUID, String>> players = new ArrayList<>();

            for (Map.Entry<UUID, String> entry : UIAssetLockPlayersPanel.this.known.entrySet())
            {
                if (entry.getKey() == null || entry.getKey().equals(self))
                {
                    continue;
                }

                players.add(entry);
            }

            players.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));

            if (players.isEmpty())
            {
                this.add(new EmptyRow());
            }

            for (Map.Entry<UUID, String> player : players)
            {
                this.add(new PlayerRow(player.getKey(), player.getValue()));
            }

            this.resize();
        }
    }

    private class EmptyRow extends UIElement
    {
        EmptyRow()
        {
            this.h(20);
        }

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            context.batcher.text(CoopL10n.key("bbs_together.locks.picker.empty").get(),
                this.area.x + 4, this.area.y + 6, Colors.GRAY, false);
        }
    }

    private class PlayerRow extends UIElement
    {
        private final UUID id;
        private final String name;

        PlayerRow(UUID id, String name)
        {
            this.id = id;
            this.name = name;

            this.h(ROW);
        }

        private boolean isSelected()
        {
            return UIAssetLockPlayersPanel.this.selected.contains(this.id);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (this.area.isInside(context) && context.mouseButton == 0)
            {
                if (UIAssetLockPlayersPanel.this.selected.contains(this.id))
                {
                    UIAssetLockPlayersPanel.this.selected.remove(this.id);
                }
                else
                {
                    UIAssetLockPlayersPanel.this.selected.add(this.id);
                }

                return true;
            }

            return super.subMouseClicked(context);
        }

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            Batcher2D batcher = context.batcher;
            boolean hover = this.area.isInside(context);
            boolean selected = this.isSelected();
            int primary = 0xFF000000 | BBSSettings.primaryColor.get();

            if (selected)
            {
                this.area.render(batcher, (primary & 0xFFFFFF) | 0x50000000);
            }
            else if (hover)
            {
                this.area.render(batcher, Colors.A25 | Colors.WHITE);
            }

            int face = this.area.y + (this.area.h - 16) / 2;

            UIPlayerFaceRenderer.draw(batcher, this.name, this.id, this.area.x + 4, face, 16);

            batcher.text(this.name, this.area.x + 26, this.area.y + (this.area.h - batcher.getFont().getHeight()) / 2 + 1,
                selected ? Colors.WHITE : Colors.LIGHTEST_GRAY, false);

            if (selected)
            {
                batcher.icon(Icons.ADD, primary,
                    this.area.ex() - 16, this.area.y + (this.area.h - 16) / 2);
            }
        }
    }
}
