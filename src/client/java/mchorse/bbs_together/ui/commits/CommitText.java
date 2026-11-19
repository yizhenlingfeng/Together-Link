package mchorse.bbs_together.ui.commits;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_together.commits.FilmCommit;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Expands server-generated, language-neutral commit messages for display.
 *
 * <p>Automatic commits are made by the server for every player, so their stored message cannot
 * be resolved to one language at commit time. The server stores a marker instead
 * ({@link FilmCommit#AUTO_PREFIX}); this class turns it into the local player's language every
 * time it is drawn, so a language switch updates the history immediately. Messages written by
 * players (or by older versions) do not start with the marker and are returned unchanged.</p>
 */
public final class CommitText
{
    private CommitText()
    {}

    public static String display(String message)
    {
        if (message == null || !message.startsWith(FilmCommit.AUTO_PREFIX))
        {
            return message;
        }

        String kind = message.substring(FilmCommit.AUTO_PREFIX.length());

        if ("import".equals(kind))
        {
            return L10n.lang("bbs_together.commits.auto.import").get();
        }

        if ("commit".equals(kind))
        {
            return L10n.lang("bbs_together.commits.auto.commit").get();
        }

        if (kind.startsWith("coop:"))
        {
            return L10n.lang("bbs_together.commits.auto_coop").format(kind.substring("coop:".length())).get();
        }

        if (kind.startsWith("summary:"))
        {
            String[] parts = kind.substring("summary:".length()).split(",");

            if (parts.length != 3)
            {
                return message;
            }

            StringBuilder builder = new StringBuilder();

            append(builder, "bbs_together.commits.auto.clips", parts[0]);
            append(builder, "bbs_together.commits.auto.replays", parts[1]);
            append(builder, "bbs_together.commits.auto.keyframes", parts[2]);

            return builder.toString();
        }

        return message;
    }

    /** A compact relative time for list rows: minutes and hours when fresh, a date after a week. */
    public static String relativeTime(long time)
    {
        long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - time);

        if (diff < 60_000L)
        {
            return L10n.lang("bbs_together.commits.time_now").get();
        }

        if (diff < 3_600_000L)
        {
            return L10n.lang("bbs_together.commits.time_minutes").format(diff / 60_000L).get();
        }

        if (diff < 86_400_000L)
        {
            return L10n.lang("bbs_together.commits.time_hours").format(diff / 3_600_000L).get();
        }

        if (diff < 7L * 86_400_000L)
        {
            return L10n.lang("bbs_together.commits.time_days").format(diff / 86_400_000L).get();
        }

        return new SimpleDateFormat("MM-dd").format(new Date(time));
    }

    private static void append(StringBuilder builder, String key, String count)
    {
        int number;

        try
        {
            number = Integer.parseInt(count.trim());
        }
        catch (NumberFormatException e)
        {
            return;
        }

        if (number <= 0)
        {
            return;
        }

        if (builder.length() > 0)
        {
            builder.append(" · ");
        }

        builder.append(L10n.lang(key).format(number).get());
    }
}
