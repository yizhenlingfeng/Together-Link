package mchorse.bbs_together.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.behaviors.PlayerRecord;
import mchorse.bbs_together.network.CoopClient;
import mchorse.bbs_together.network.TogetherClientNetwork;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Request cooperative recording" player picker: every online player as an
 * avatar + name row, click to toggle, multi-select, then invite. The film id
 * travels with the invite — the picking room, not this dialog, sorts out who
 * records which replay.
 */
@Environment(EnvType.CLIENT)
public class UICoopInvitePanel extends UIOverlayPanel
{
    private static final int ROW = 22;

    private final String filmId;
    private final Set<UUID> selected = new java.util.LinkedHashSet<>();
    private final PlayerList list;
    private final UIButton invite;

    public UICoopInvitePanel(String filmId)
    {
        super(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.invite.title"));

        this.filmId = filmId;

        this.list = new PlayerList();
        this.list.relative(this.content).xy(0, 0).w(1F).h(1F, -26);

        UIElement buttons = mchorse.bbs_mod.ui.utils.UI.row(4, this.invite = this.createInviteButton(), this.createCancelButton());

        buttons.relative(this.content).x(0).y(1F, -24).w(1F).h(22);

        this.content.add(this.list, buttons);
        this.list.refresh();
    }

    private UIButton createInviteButton()
    {
        UIButton button = new UIButton(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.invite.send"), (b) -> this.send());

        button.setEnabled(false);

        return button;
    }

    private UIButton createCancelButton()
    {
        return new UIButton(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.invite.cancel"), (b) -> this.close());
    }

    private void send()
    {
        CoopClient.sendInvite(this.filmId, new ArrayList<>(this.selected));
        this.close();
    }

    private void updateInviteEnabled()
    {
        this.invite.setEnabled(!this.selected.isEmpty());
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
            UICoopInvitePanel.this.selected.clear();

            UUID self = MinecraftClient.getInstance().player == null
                ? null
                : MinecraftClient.getInstance().player.getUuid();

            List<PlayerRecord> players = new ArrayList<>();

            for (PlayerRecord record : TogetherClientNetwork.behaviorPlayers.values())
            {
                if (record.groupHeader || record.id == null || record.id.equals(self))
                {
                    continue;
                }

                players.add(record);
            }

            players.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

            if (players.isEmpty())
            {
                this.add(new EmptyRow());
            }

            for (PlayerRecord record : players)
            {
                this.add(new PlayerRow(record));
            }

            this.resize();
        }
    }

    private class EmptyRow extends UIElement
    {
        public EmptyRow()
        {
            this.h(20);
        }

        @Override
        public void render(UIContext context)
        {
            super.render(context);

            context.batcher.text(mchorse.bbs_together.ui.CoopL10n.key("bbs_together.coop.invite.empty").get(),
                this.area.x + 4, this.area.y + 6, Colors.GRAY, false);
        }
    }

    private class PlayerRow extends UIElement
    {
        private final PlayerRecord record;

        public PlayerRow(PlayerRecord record)
        {
            this.record = record;

            this.h(ROW);
        }

        private boolean isSelected()
        {
            return UICoopInvitePanel.this.selected.contains(this.record.id);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (this.area.isInside(context) && context.mouseButton == 0)
            {
                if (UICoopInvitePanel.this.selected.contains(this.record.id))
                {
                    UICoopInvitePanel.this.selected.remove(this.record.id);
                }
                else
                {
                    UICoopInvitePanel.this.selected.add(this.record.id);
                }

                UICoopInvitePanel.this.updateInviteEnabled();

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

            UIPlayerFaceRenderer.draw(batcher, this.record.name, this.record.id, this.area.x + 4, face, 16);

            String label = this.record.name;

            batcher.text(label, this.area.x + 26, this.area.y + (this.area.h - batcher.getFont().getHeight()) / 2 + 1,
                selected ? Colors.WHITE : Colors.LIGHTEST_GRAY, false);

            if (selected)
            {
                batcher.icon(mchorse.bbs_mod.ui.utils.icons.Icons.ADD, primary,
                    this.area.ex() - 16, this.area.y + (this.area.h - 16) / 2);
            }
        }
    }
}
