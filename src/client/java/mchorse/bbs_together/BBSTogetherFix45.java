package mchorse.bbs_together;

import java.util.List;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;

public final class BBSTogetherFix45 {
    private BBSTogetherFix45() {
    }

    public static int identityReplayIndex(List<Replay> list, Replay replay) {
        if (list == null || replay == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); ++i) {
            if (list.get(i) != replay) continue;
            return i;
        }
        return -1;
    }

    public static int currentReplayIndex(UIFilmPanel uIFilmPanel, Film film) {
        if (uIFilmPanel == null || uIFilmPanel.replayEditor == null || film == null) {
            return -1;
        }
        int n = BBSTogetherFix45.identityReplayIndex(film.replays.getList(), uIFilmPanel.replayEditor.getReplay());
        if (n >= 0) {
            return n;
        }
        if (uIFilmPanel.replayEditor.replaysList != null && uIFilmPanel.replayEditor.replaysList.replays != null) {
            Replay current = uIFilmPanel.replayEditor.replaysList.replays.getSelectedReplayFirst();
            return current == null ? -1 : film.replays.getList().indexOf(current);
        }
        return -1;
    }
}

