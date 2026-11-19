package mchorse.bbs_together.ui;

import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_together.locks.KeyframeLockClient;
import mchorse.bbs_together.locks.KeyframeLockData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Read-only lock mask. One lock record spans several channels, and the dope sheet
 * stacks those channels as touching rows, so drawing one dashed box per row left a
 * dashed horizontal line between every pair of locked rows. The mask is therefore
 * drawn as the union of all lock rectangles: only the outer boundary of the merged
 * shape gets a dashed outline (and the outer fill), internal shared edges and the
 * seams between rows disappear.
 *
 * <p>The padlock in the corner of each merged shape follows the player the same way the
 * context menu and the clip bars do: closed while the lock holds them back, open when it
 * does not, so a whitelisted editor can tell a lock apart from a wall.</p>
 */
@Environment(EnvType.CLIENT)
public final class KeyframeLockOverlay
{
    private static final int DASH = 4;
    private static final int GAP = 4;

    private KeyframeLockOverlay()
    {}

    public static void render(UIKeyframes view, UIContext context)
    {
        if (view == null || view.getGraph() == null)
        {
            return;
        }

        IUIKeyframeGraph graph = view.getGraph();
        int clipX1 = view.graphArea.x;
        int clipX2 = view.graphArea.ex();
        int top = view.graphArea.y + IUIKeyframeGraph.TOP_MARGIN;
        int bottomLimit = view.graphArea.ey();

        List<int[]> boxes = new ArrayList<>();
        boolean enforced = false;

        KeyframeLockClient.renderFilm(KeyframeLockClient.film(view));

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            ValueGroup owner = KeyframeLockData.ownerOf(sheet.channel);

            if (owner == null)
            {
                continue;
            }

            List<KeyframeLockData> locks = KeyframeLockData.get(owner);

            if (locks.isEmpty())
            {
                continue;
            }

            int[] row = KeyframeLockClient.row(view, sheet);
            int bottom = row[0] + row[1];

            for (KeyframeLockData lock : locks)
            {
                for (KeyframeLockData.Range range : lock.ranges())
                {
                    if (!range.channel().equals(sheet.channel.getId()))
                    {
                        continue;
                    }

                    int x1 = Math.max(clipX1, view.toGraphX(range.start()));
                    int x2 = Math.min(clipX2, view.toGraphX(range.end()));
                    int y1 = Math.max(top, row[0]);
                    int y2 = Math.min(bottomLimit, bottom);

                    if (x2 - x1 < 2 || y2 - y1 < 2)
                    {
                        continue;
                    }

                    boxes.add(new int[] {x1, y1, x2, y2});

                    if (KeyframeLockClient.lockedForSelf(sheet))
                    {
                        enforced = true;
                    }
                }
            }
        }

        if (!boxes.isEmpty())
        {
            renderUnion(context, boxes, enforced);
        }
    }

    /* Union in pixel space: break every rectangle into grid cells, then draw the fill
     * and only the cell sides whose neighbour across that side is empty. */
    private static void renderUnion(UIContext context, List<int[]> boxes, boolean enforced)
    {
        TreeSet<Integer> xsSet = new TreeSet<>();
        TreeSet<Integer> ysSet = new TreeSet<>();

        for (int[] box : boxes)
        {
            xsSet.add(box[0]);
            xsSet.add(box[2]);
            ysSet.add(box[1]);
            ysSet.add(box[3]);
        }

        int[] xs = toArray(xsSet);
        int[] ys = toArray(ysSet);
        int nx = xs.length - 1;
        int ny = ys.length - 1;

        boolean[][] covered = new boolean[ny][nx];

        for (int[] box : boxes)
        {
            int j1 = indexOf(xs, box[0]);
            int j2 = indexOf(xs, box[2]);
            int i1 = indexOf(ys, box[1]);
            int i2 = indexOf(ys, box[3]);

            for (int i = i1; i < i2; i++)
            {
                for (int j = j1; j < j2; j++)
                {
                    covered[i][j] = true;
                }
            }
        }

        int fill = Colors.setA(Colors.ORANGE, 0.10F);

        for (int i = 0; i < ny; i++)
        {
            for (int j = 0; j < nx; j++)
            {
                if (!covered[i][j])
                {
                    continue;
                }

                int runEnd = j;

                while (runEnd + 1 < nx && covered[i][runEnd + 1])
                {
                    runEnd += 1;
                }

                context.batcher.box(xs[j], ys[i], xs[runEnd + 1], ys[i + 1], fill);

                j = runEnd;
            }
        }

        Map<Integer, List<int[]>> horizontal = new HashMap<>();
        Map<Integer, List<int[]>> vertical = new HashMap<>();

        for (int i = 0; i < ny; i++)
        {
            for (int j = 0; j < nx; j++)
            {
                if (!covered[i][j])
                {
                    continue;
                }

                if (i == 0 || !covered[i - 1][j])
                {
                    addSegment(horizontal, ys[i], xs[j], xs[j + 1], 1);
                }

                if (i == ny - 1 || !covered[i + 1][j])
                {
                    addSegment(horizontal, ys[i + 1], xs[j], xs[j + 1], 0);
                }

                if (j == 0 || !covered[i][j - 1])
                {
                    addSegment(vertical, xs[j], ys[i], ys[i + 1], 1);
                }

                if (j == nx - 1 || !covered[i][j + 1])
                {
                    addSegment(vertical, xs[j + 1], ys[i], ys[i + 1], 0);
                }
            }
        }

        int strong = Colors.setA(Colors.ORANGE, 0.85F);
        int soft = Colors.setA(Colors.ORANGE, 0.55F);
        Icon padlock = enforced ? Icons.LOCKED : Icons.UNLOCKED;

        drawDashes(context, horizontal, true, strong, soft);
        drawDashes(context, vertical, false, strong, soft);

        renderPadlocks(context, covered, xs, ys, nx, ny, strong, padlock);
    }

    private static void renderPadlocks(UIContext context, boolean[][] covered, int[] xs, int[] ys, int nx, int ny, int color, Icon padlock)
    {
        int[][] ids = new int[ny][nx];
        Deque<int[]> queue = new ArrayDeque<>();
        int next = 0;

        for (int i = 0; i < ny; i++)
        {
            for (int j = 0; j < nx; j++)
            {
                if (!covered[i][j] || ids[i][j] != 0)
                {
                    continue;
                }

                next += 1;

                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;

                ids[i][j] = next;
                queue.add(new int[] {i, j});

                while (!queue.isEmpty())
                {
                    int[] cell = queue.poll();
                    int ci = cell[0];
                    int cj = cell[1];

                    minX = Math.min(minX, xs[cj]);
                    minY = Math.min(minY, ys[ci]);
                    maxX = Math.max(maxX, xs[cj + 1]);
                    maxY = Math.max(maxY, ys[ci + 1]);

                    visit(covered, ids, queue, next, ci - 1, cj);
                    visit(covered, ids, queue, next, ci + 1, cj);
                    visit(covered, ids, queue, next, ci, cj - 1);
                    visit(covered, ids, queue, next, ci, cj + 1);
                }

                if (maxX - minX >= 18 && maxY - minY >= 12)
                {
                    context.batcher.scaledIcon(padlock, color, minX + 3, minY + 3, 9);
                }
            }
        }
    }

    private static void visit(boolean[][] covered, int[][] ids, Deque<int[]> queue, int id, int i, int j)
    {
        if (i < 0 || j < 0 || i >= covered.length || j >= covered[0].length
            || !covered[i][j] || ids[i][j] != 0)
        {
            return;
        }

        ids[i][j] = id;
        queue.add(new int[] {i, j});
    }

    private static void addSegment(Map<Integer, List<int[]>> segments, int key, int a, int b, int flag)
    {
        segments.computeIfAbsent(key, (k) -> new ArrayList<>()).add(new int[] {a, b, flag});
    }

    private static void drawDashes(UIContext context, Map<Integer, List<int[]>> segments, boolean horizontal, int strong, int soft)
    {
        for (Map.Entry<Integer, List<int[]>> entry : segments.entrySet())
        {
            List<int[]> runs = entry.getValue();

            runs.sort(Comparator.comparingInt((int[] run) -> run[0]));

            List<int[]> merged = new ArrayList<>();
            int[] current = null;

            for (int[] run : runs)
            {
                if (current == null || run[0] > current[1])
                {
                    current = new int[] {run[0], run[1]};
                    merged.add(current);
                }
                else
                {
                    current[1] = Math.max(current[1], run[1]);
                }
            }

            int offset = 0;

            for (int[] run : merged)
            {
                int length = run[1] - run[0];
                int drawn = 0;
                int phase = Math.floorMod(offset, DASH + GAP);

                while (drawn < length)
                {
                    int dashLength = Math.min(DASH - phase, length - drawn);

                    if (dashLength <= 0)
                    {
                        break;
                    }

                    int from = run[0] + drawn;
                    int to = from + dashLength;

                    if (horizontal)
                    {
                        context.batcher.box(from, entry.getKey() - 0.5F, to, entry.getKey() + 0.5F, (drawn + phase) < DASH ? strong : soft);
                    }
                    else
                    {
                        context.batcher.box(entry.getKey() - 0.5F, from, entry.getKey() + 0.5F, to, (drawn + phase) < DASH ? strong : soft);
                    }

                    drawn += dashLength;
                    phase = 0;
                }

                offset += length;
            }
        }
    }

    private static int[] toArray(TreeSet<Integer> set)
    {
        int[] array = new int[set.size()];
        int i = 0;

        for (int value : set)
        {
            array[i++] = value;
        }

        return array;
    }

    private static int indexOf(int[] array, int value)
    {
        for (int i = 0; i < array.length; i++)
        {
            if (array[i] == value)
            {
                return i;
            }
        }

        return -1;
    }
}
