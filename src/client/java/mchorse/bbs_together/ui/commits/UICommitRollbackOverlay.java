package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * A confirmation dialog with more than one way out: the message explains what will happen, and
 * each choice is a full width button stacked under it.
 */
public class UICommitRollbackOverlay extends UIMessageOverlayPanel
{
    /** One button of the dialog: what it says, and what it does. */
    public static final class Choice
    {
        public final IKey label;
        public final Runnable action;

        public Choice(IKey label, Runnable action)
        {
            this.label = label;
            this.action = action;
        }
    }

    private final List<Choice> choices = new ArrayList<Choice>();

    public UICommitRollbackOverlay(IKey title, IKey message)
    {
        super(title, message);
    }

    public UICommitRollbackOverlay choice(IKey label, Runnable action)
    {
        this.choices.add(new Choice(label, action));

        return this;
    }

    /**
     * Lays the buttons out under the message. The panel measures itself from {@code bottom}, so
     * this has to run after every {@link #choice(IKey, Runnable)} and before the overlay is added.
     */
    public UICommitRollbackOverlay build()
    {
        UIElement block = new UIElement();
        int height = Math.max(0, this.choices.size() * 22 - 4);

        block.relative(this.content).x(0.5F).y(1F, -10).w(0.7F).h(height).anchor(0.5F, 1F);

        for (int i = 0; i < this.choices.size(); i ++)
        {
            final Choice choice = this.choices.get(i);
            UIButton button = new UIButton(choice.label, (b) -> this.pick(choice));

            button.relative(block).x(0).y(i * 22).w(1F).h(18);
            block.add(button);
        }

        this.content.add(block);
        this.bottom = block;

        return this;
    }

    private void pick(Choice choice)
    {
        this.close();

        choice.action.run();
    }
}
