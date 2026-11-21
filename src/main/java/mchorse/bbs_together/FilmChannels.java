package mchorse.bbs_together;

import net.minecraft.util.Identifier;

/**
 * Wire channels used by the film join / snapshot flow.
 *
 * <p>The {@code _fixNN} suffixes are part of the protocol on the wire: a client and a server
 * only talk when both register the exact same identifier string. Never rename them without a
 * protocol version bump and a matching release on both sides.</p>
 */
public final class FilmChannels {
    public static final Identifier SERVER_FILM_JOIN_REQUEST = Identifier.of("bbs_together", "s_film_join_request_fix28");
    public static final Identifier CLIENT_FILM_JOIN_SOURCE_REQUEST = Identifier.of("bbs_together", "c_film_join_source_request_fix28");
    public static final Identifier SERVER_FILM_JOIN_SNAPSHOT = Identifier.of("bbs_together", "s_film_join_snapshot_fix28");
    public static final Identifier SERVER_FILM_LEAVE_SNAPSHOT = Identifier.of("bbs_together", "s_film_leave_snapshot_fix30");
    public static final Identifier SERVER_FILM_STRUCTURE_SNAPSHOT = Identifier.of("bbs_together", "s_film_structure_snapshot_fix34");
    public static final Identifier SERVER_RECORDING_BASELINE_SNAPSHOT = Identifier.of("bbs_together", "s_recording_baseline_snapshot_fix64");

    private FilmChannels() {
    }
}

