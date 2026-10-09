package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.overlays.UIKeyframeLoopOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.DoubleClick;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoops;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interaction and drawing of finite loop blocks, shared by every keyframe timeline. */
public class UIKeyframeLoops
{
    private final UIKeyframes view;
    private final DoubleClick<String> doubleClick = new DoubleClick<>(false);
    private KeyframeChannel<?> selectedChannel;
    private String selectedId;
    private Map<KeyframeChannel<?>, BaseType> before;
    private List<KeyframeChannel<?>> draggingChannels;
    private float pressTick;
    private float originalStart;
    private float originalEnd;
    private boolean resizing;

    public UIKeyframeLoops(UIKeyframes view)
    {
        this.view = view;
    }

    private record Hit(UIKeyframeSheet sheet, KeyframeLoop loop, int y, int height) {}

    private boolean connectsTo(UIKeyframeSheet sheet, KeyframeLoop loop, int neighborY)
    {
        if (this.view.getGraph() != this.view.getDopeSheet()) return false;
        UIKeyframeSheet neighbor = this.view.getDopeSheet().getSheet(neighborY);
        if (neighbor == null || neighbor == sheet) return false;
        KeyframeLoop other = neighbor.channel.getLoop(loop.id());

        return other != null && loop.start() == other.start() && loop.sourceEnd() == other.sourceEnd()
            && sheet.channel.getLoopEnd(loop) == neighbor.channel.getLoopEnd(other);
    }

    private Hit hit(UIContext context)
    {
        if (!this.view.graphArea.isInside(context) || context.mouseY < this.view.area.y + IUIKeyframeGraph.TOP_MARGIN) return null;
        UIKeyframeSheet sheet = this.view.getGraph().getSheet(context.mouseY);
        if (sheet == null) return null;
        boolean dope = this.view.getGraph() == this.view.getDopeSheet();
        int y = dope ? this.view.getDopeSheet().getDopeSheetY(sheet) : this.view.area.y + IUIKeyframeGraph.TOP_MARGIN;
        int height = dope ? this.view.getDopeSheet().getTrackHeight(sheet) : 16;

        for (Object entry : sheet.channel.getLoops())
        {
            KeyframeLoop loop = (KeyframeLoop) entry;
            int start = this.view.toGraphX(loop.start()), end = this.view.toGraphX(sheet.channel.getLoopEnd(loop));
            if (context.mouseX >= start - 3 && context.mouseX <= end + 4) return new Hit(sheet, loop, y, height);
        }
        return null;
    }

    public void menu(ContextMenuManager menu, UIContext context)
    {
        List<KeyframeChannel<?>> channels = new ArrayList<>();
        float start = Float.POSITIVE_INFINITY, end = Float.NEGATIVE_INFINITY;
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (!sheet.selection.hasAny()) continue;
            channels.add(sheet.channel);
            for (Keyframe key : sheet.selection.getSelected())
            {
                start = Math.min(start, key.getTick());
                end = Math.max(end, key.getTick());
            }
        }

        /* A block owns a continuous source interval; don't silently include unselected keys. */
        boolean complete = true;
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            if (!sheet.selection.hasAny()) continue;
            for (Object entry : sheet.channel.getKeyframes())
            {
                Keyframe key = (Keyframe) entry;
                if (key.getTick() >= start && key.getTick() <= end && !sheet.selection.has(key)) complete = false;
            }
        }
        boolean canCreate = complete && KeyframeLoops.canCreate(channels, start, end);
        String problem = end <= start ? "selection_duration" : !complete ? "selection_range" : "selection_overlap";
        if (!channels.isEmpty())
        {
            float sourceStart = start, sourceEnd = end;
            menu.action(Icons.REFRESH, L10n.lang("bbs.ui.keyframes.loop.create"), () ->
            {
                if (!canCreate)
                {
                    context.notify(L10n.lang("bbs.ui.keyframes.loop." + problem), Colors.BLUE);
                    return;
                }
                this.edit(channels, () ->
                {
                    KeyframeLoop loop = KeyframeLoops.create(channels, sourceStart, sourceEnd);
                    this.selectedChannel = channels.get(0);
                    this.selectedId = loop.id();
                });
                this.view.getGraph().clearSelection();
            });
        }

        Hit hit = this.hit(context);
        if (hit != null)
        {
            menu.action(Icons.EDIT, L10n.lang("bbs.ui.keyframes.loop.edit"), () -> this.open(hit.sheet.channel, hit.loop.id()));
            menu.action(Icons.REMOVE, L10n.lang("bbs.ui.keyframes.loop.remove"), () -> this.remove(hit.sheet.channel, hit.loop.id()));
        }
    }

    public void open(KeyframeChannel<?> channel, String id)
    {
        this.selectedChannel = channel;
        this.selectedId = id;
        UIOverlay.addOverlay(this.view.getContext(), new UIKeyframeLoopOverlay(this, channel, id), 240, 164);
    }

    public void setEnd(KeyframeChannel<?> channel, String id, float end)
    {
        List<KeyframeChannel<?>> members = KeyframeLoops.members(channel, id);
        this.edit(members, () -> KeyframeLoops.resize(members, id, end));
    }

    public void remove(KeyframeChannel<?> channel, String id)
    {
        List<KeyframeChannel<?>> members = KeyframeLoops.members(channel, id);
        this.edit(members, () -> members.forEach(member -> member.removeLoop(id)));
        this.selectedId = null;
    }

    private Map<KeyframeChannel<?>, BaseType> snapshot(List<KeyframeChannel<?>> channels)
    {
        Map<KeyframeChannel<?>, BaseType> result = new LinkedHashMap<>();
        for (KeyframeChannel<?> channel : channels) result.put(channel, channel.toData());
        return result;
    }

    private void edit(List<KeyframeChannel<?>> channels, Runnable edit)
    {
        for (KeyframeChannel<?> channel : channels) channel.preNotify(IValueListener.FLAG_UNMERGEABLE);
        edit.run();
        for (KeyframeChannel<?> channel : channels) channel.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.view.triggerChange();
    }

    public boolean mouseClicked(UIContext context)
    {
        if (context.mouseButton != 0) return false;
        Hit hit = this.hit(context);
        if (hit == null)
        {
            this.selectedId = null;
            return false;
        }
        int endX = this.view.toGraphX(hit.sheet.channel.getLoopEnd(hit.loop));
        boolean handle = Math.abs(context.mouseX - endX) <= 5 && context.mouseY >= hit.y && context.mouseY < hit.y + hit.height;
        boolean bar = context.mouseY >= hit.y && context.mouseY < hit.y + hit.height
            && ((context.mouseY < hit.y + 4 && !this.connectsTo(hit.sheet, hit.loop, hit.y - 1))
                || (context.mouseY >= hit.y + hit.height - 3 && !this.connectsTo(hit.sheet, hit.loop, hit.y + hit.height)));
        if (!handle && (Window.isCtrlPressed() || Window.isAltPressed() || Window.isShiftPressed())) return false;

        /* Original keys retain ordinary selection and dragging. */
        if (!handle && !bar && this.view.getGraph().findKeyframe(context.mouseX, context.mouseY) != null)
        {
            this.selectedId = null;
            return false;
        }

        this.view.getGraph().clearSelection();
        this.selectedChannel = hit.sheet.channel;
        this.selectedId = hit.loop.id();
        if (this.doubleClick.hit(this.selectedId))
        {
            this.open(this.selectedChannel, this.selectedId);
            return true;
        }

        if (!handle && !bar)
        {
            float local = hit.loop.sourceTick((float) this.view.fromGraphX(context.mouseX));
            for (Object entry : hit.sheet.channel.getKeyframes())
            {
                Keyframe key = (Keyframe) entry;
                int y = this.view.getGraph() instanceof UIKeyframeGraph graph ? graph.toGraphY(key.getY()) : hit.y + hit.height / 2;
                if (hit.loop.containsSource(key.getTick()) && Math.abs(this.view.toGraphX(local) - this.view.toGraphX(key.getTick())) <= 5 && Math.abs(context.mouseY - y) <= 5)
                {
                    hit.sheet.selection.add(key);
                    this.view.pickKeyframe(key);
                    return true;
                }
            }
        }

        this.draggingChannels = KeyframeLoops.members(this.selectedChannel, this.selectedId);
        this.before = this.snapshot(this.draggingChannels);
        this.pressTick = (float) this.view.fromGraphX(context.mouseX);
        this.originalStart = hit.loop.start();
        this.originalEnd = hit.sheet.channel.getLoopEnd(hit.loop);
        this.resizing = handle;
        return true;
    }

    public boolean isDragging()
    {
        return this.before != null;
    }

    public void handleMouse(UIContext context)
    {
        if (!this.isDragging()) return;
        KeyframeLoop loop = this.selectedChannel.getLoop(this.selectedId);
        if (loop == null) return;
        float delta = (float) this.view.fromGraphX(context.mouseX) - this.pressTick;
        if (this.view.isSnappingToTicks()) delta = Math.round(delta);
        if (this.resizing)
        {
            float end = this.originalEnd + delta;
            if (Window.isCtrlPressed()) end = loop.start() + Math.max(1, Math.round((end - loop.start()) / loop.period())) * loop.period();
            KeyframeLoops.resize(this.draggingChannels, this.selectedId, end);
        }
        else KeyframeLoops.move(this.draggingChannels, this.selectedId, this.originalStart + delta - loop.start());
        this.view.triggerChange();
    }

    public boolean release(boolean cancel)
    {
        if (!this.isDragging()) return false;
        Map<KeyframeChannel<?>, BaseType> after = this.snapshot(this.draggingChannels);
        boolean changed = !this.before.equals(after);
        if (cancel || changed)
        {
            this.before.forEach(KeyframeChannel::fromData);
            if (!cancel) this.edit(this.draggingChannels, () -> after.forEach(KeyframeChannel::fromData));
        }
        this.before = null;
        this.draggingChannels = null;
        this.view.triggerChange();
        return true;
    }

    public boolean keyPressed(UIContext context)
    {
        if (this.isDragging()) return context.isPressed(GLFW.GLFW_KEY_ESCAPE) ? this.release(true) : true;
        if (this.view.area.isInside(context) && this.selectedId != null && this.view.getGraph().getSelected() == null && context.isPressed(GLFW.GLFW_KEY_DELETE))
        {
            this.remove(this.selectedChannel, this.selectedId);
            return true;
        }
        return false;
    }

    public void reset()
    {
        this.release(false);
        this.selectedId = null;
        this.selectedChannel = null;
    }

    public void render(UIContext context)
    {
        Hit hovered = this.hit(context);
        if (this.isDragging()) context.requestCursor(this.resizing ? GLFW.GLFW_HRESIZE_CURSOR : GLFW.GLFW_HAND_CURSOR);
        else if (hovered != null && context.mouseY >= hovered.y && context.mouseY < hovered.y + hovered.height
            && Math.abs(context.mouseX - this.view.toGraphX(hovered.sheet.channel.getLoopEnd(hovered.loop))) <= 5)
        {
            context.requestCursor(GLFW.GLFW_HRESIZE_CURSOR);
        }
        boolean dope = this.view.getGraph() == this.view.getDopeSheet();
        int rulerBottom = this.view.area.y + IUIKeyframeGraph.TOP_MARGIN;
        context.batcher.clipBox(this.view.graphArea.x, rulerBottom, this.view.graphArea.ex(), this.view.graphArea.ey(), context);
        for (UIKeyframeSheet sheet : this.view.getGraph().getSheets())
        {
            int y = dope ? this.view.getDopeSheet().getDopeSheetY(sheet) : rulerBottom;
            int height = dope ? this.view.getDopeSheet().getTrackHeight(sheet) : 16;
            if (dope && this.view.getDopeSheet().getSheet(y + height / 2) != sheet) continue;
            if (y + height < rulerBottom || y > this.view.area.ey()) continue;

            for (Object entry : sheet.channel.getLoops())
            {
                KeyframeLoop loop = (KeyframeLoop) entry;
                float endTick = sheet.channel.getLoopEnd(loop);
                int start = this.view.toGraphX(loop.start()), end = this.view.toGraphX(endTick);
                if (end < this.view.graphArea.x || start > this.view.graphArea.ex()) continue;
                int sourceEnd = this.view.toGraphX(loop.sourceEnd());
                boolean selected = loop.id().equals(this.selectedId);
                boolean hover = hovered != null && hovered.loop.id().equals(loop.id())
                    && context.mouseY >= hovered.y && context.mouseY < hovered.y + hovered.height;
                boolean highlight = selected || hover;
                int color = selected ? Colors.ACTIVE : sheet.color;
                boolean joinsAbove = this.connectsTo(sheet, loop, y - 1);
                boolean joinsBelow = this.connectsTo(sheet, loop, y + height);
                int top = joinsAbove ? y : y + 1;
                int bottom = joinsBelow ? y + height : y + height - 1;

                context.batcher.box(start, top, end, bottom, Colors.setA(color, 0.12F));
                if (!joinsAbove) context.batcher.box(start, top, end, top + 1, highlight ? Colors.WHITE : Colors.setA(color, 0.9F));
                if (!joinsBelow) context.batcher.box(start, bottom - 1, end, bottom, highlight ? Colors.WHITE : Colors.setA(color, 0.7F));
                context.batcher.box(start, top, start + 1, bottom, highlight ? Colors.WHITE : Colors.setA(color, 0.8F));
                context.batcher.box(sourceEnd, joinsAbove ? top : y + 3, sourceEnd + 1, joinsBelow ? bottom : y + height - 2, Colors.setA(color, 0.5F));
                context.batcher.box(end - 2, top, end + 2, bottom, highlight ? Colors.WHITE : Colors.setA(color, 1F));

                this.renderGhosts(context, sheet, loop, endTick, y + height / 2);
            }
        }
        context.batcher.unclip(context);
    }

    public void renderStatus(UIContext context)
    {
        if (this.isDragging())
        {
            KeyframeLoop loop = this.selectedChannel.getLoop(this.selectedId);
            if (loop != null)
            {
                String label = L10n.lang("bbs.ui.keyframes.loop.status").format(String.format(java.util.Locale.ROOT, "%.2f", loop.end()), String.format(java.util.Locale.ROOT, "%.2f", loop.passes())).get();
                int width = context.batcher.getFont().getWidth(label);
                int height = context.batcher.getFont().getHeight();
                int x = Math.max(this.view.area.x + 4, Math.min(context.mouseX + 12, this.view.area.ex() - width - 4));
                int y = context.mouseY + 12;
                if (y + height + 4 > this.view.area.ey()) y = context.mouseY - height - 12;
                y = Math.max(this.view.area.y + 4, y);
                context.batcher.textCard(label, x, y, Colors.WHITE, 0xE6181818);
            }
        }
    }

    private void renderGhosts(UIContext context, UIKeyframeSheet sheet, KeyframeLoop loop, float end, int rowY)
    {
        double period = loop.period();
        double firstTick = this.view.fromGraphX(this.view.graphArea.x - 6);
        double lastTick = Math.min(end, this.view.fromGraphX(this.view.graphArea.ex() + 6));
        double pixelPeriod = Math.abs(this.view.toGraphX(loop.sourceEnd()) - this.view.toGraphX(loop.start()));
        double stride = Math.max(1, Math.ceil(8 / Math.max(0.001, pixelPeriod)));
        BufferBuilder builder = Tessellator.getInstance().getBuffer();
        Matrix4f matrix = context.batcher.getContext().getMatrices().peek().getPositionMatrix();
        builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int drawn = 0;
        for (Object entry : sheet.channel.getKeyframes())
        {
            Keyframe key = (Keyframe) entry;
            if (!loop.containsSource(key.getTick())) continue;
            double first = Math.max(1, Math.ceil((firstTick - key.getTick()) / period));
            int y = this.view.getGraph() instanceof UIKeyframeGraph graph ? graph.toGraphY(key.getY()) : rowY;
            for (double pass = first; drawn < 3000; pass += stride)
            {
                double tick = key.getTick() + pass * period;
                if (tick > lastTick) break;
                if (tick <= loop.sourceEnd()) continue;
                UIKeyframeDopeSheet.renderShape(key, context, builder, matrix, this.view.toGraphX(tick), y, 3, Colors.setA(sheet.color, 0.35F));
                drawn++;
            }
        }
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(builder.end());
    }
}
