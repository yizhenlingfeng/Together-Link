package mchorse.bbs_together;

import java.util.List;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;

/**
 * Resolves a value from a path, one segment at a time, starting at the given root.
 *
 * <p>{@link BaseValueGroup#findRecursively} cannot be used for this: it looks up only the first
 * segment and falls back to a "skip the first segment" deep search when that misses. Given a film
 * as the root, any path below a direct child (for example {@code replays/<replay>/...} or
 * {@code camera/<clip>/...}) therefore resolves to that child group instead of the value that
 * actually sits at the end of the path. The segments are the ids of the values on the way down,
 * so a plain walk is enough.</p>
 */
public final class BBSTogetherFix67UndoPath {
    private BBSTogetherFix67UndoPath() {
    }

    public static BaseValue resolve(BaseValue root, List<String> path) {
        if (root == null || path == null) {
            return null;
        }

        BaseValue current = root;

        for (String segment : path) {
            if (!(current instanceof BaseValueGroup)) {
                return null;
            }

            current = ((BaseValueGroup) current).get(segment);

            if (current == null) {
                return null;
            }
        }

        return current;
    }
}
