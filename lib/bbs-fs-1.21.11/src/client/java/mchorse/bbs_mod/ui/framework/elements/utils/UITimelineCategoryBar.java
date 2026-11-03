package mchorse.bbs_mod.ui.framework.elements.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;

import java.util.List;

/** A timeline's category buttons, wrapping into columns when height is limited. */
public class UITimelineCategoryBar extends UIElement
{
    public static final int BUTTON_SIZE = 20;
    private static final int SEPARATOR_HEIGHT = 7;

    private final int bottomSpace;

    public UITimelineCategoryBar(int bottomSpace)
    {
        this.bottomSpace = bottomSpace;
        this.w(BUTTON_SIZE).h(1F);
    }

    public int getWidthForHeight(int height)
    {
        int required = this.getChildren(UIIcon.class).size() * BUTTON_SIZE + SEPARATOR_HEIGHT + this.bottomSpace;

        if (height >= required) return BUTTON_SIZE;

        int rows = Math.max(1, (height - this.bottomSpace) / BUTTON_SIZE);
        int columns = Math.max(2, (this.getChildren(UIIcon.class).size() + rows - 1) / rows);

        return BUTTON_SIZE * columns;
    }

    @Override
    protected void afterResizeApplied()
    {
        List<UIIcon> buttons = this.getChildren(UIIcon.class);
        int columns = this.area.w / BUTTON_SIZE;

        for (int i = 0; i < buttons.size(); i++)
        {
            int y = (i / columns) * BUTTON_SIZE;

            if (columns == 1 && i > 0)
            {
                y += SEPARATOR_HEIGHT;
            }

            buttons.get(i).relative(this).x((i % columns) * BUTTON_SIZE).y(y).wh(BUTTON_SIZE, BUTTON_SIZE);
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.chromeSurface());

        if (this.area.w == BUTTON_SIZE)
        {
            int y = this.area.y + BUTTON_SIZE + SEPARATOR_HEIGHT / 2;

            context.batcher.box(this.area.x + 4, y, this.area.ex() - 4, y + 1, BBSSettings.dividerColor());
        }

        super.render(context);
    }
}
