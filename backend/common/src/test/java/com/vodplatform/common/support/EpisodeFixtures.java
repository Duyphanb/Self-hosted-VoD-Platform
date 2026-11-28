package com.vodplatform.common.support;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class EpisodeFixtures {
    private EpisodeFixtures() {
    }

    public static UUID episode(JdbcTemplate jdbc, UUID title) {
        return jdbc.query("select id from episodes where title_id = ? and ordinal = 1",
                (row, index) -> row.getObject(1, UUID.class), title).stream().findFirst().orElse(title);
    }

    public static UUID addFilmEpisode(JdbcTemplate jdbc, UUID title) {
        UUID episode = UUID.randomUUID();
        jdbc.update("""
                insert into episodes (id,title_id,ordinal,display_label,status,created_at,updated_at)
                values (?, ?, 1, '1', 'PUBLISHED', now(), now())
                """, episode, title);
        return episode;
    }

    public static void removeTitle(JdbcTemplate jdbc, UUID title) {
        for (String table : List.of("playback_progress", "watch_history", "video_assets")) {
            jdbc.update("delete from " + table + " where episode_id in (select id from episodes where title_id=?)",
                    title);
        }
        jdbc.update("delete from episodes where title_id=?", title);
        jdbc.update("delete from titles where id=?", title);
    }
}
