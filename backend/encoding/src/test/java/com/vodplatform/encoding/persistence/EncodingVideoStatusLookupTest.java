package com.vodplatform.encoding.persistence;

import static com.vodplatform.encoding.persistence.EncodingFixtures.T0;
import static com.vodplatform.encoding.persistence.EncodingFixtures.asset;
import static com.vodplatform.encoding.persistence.EncodingFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;

import com.vodplatform.encoding.config.EncodingConfiguration;
import com.vodplatform.catalog.config.CatalogConfiguration;
import com.vodplatform.encoding.service.EncodingVideoStatusLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.vodplatform.encoding.persistence.RecordingStatementInspector")
@ContextConfiguration(classes = {EncodingConfiguration.class, CatalogConfiguration.class, EncodingVideoStatusLookup.class})
class EncodingVideoStatusLookupTest {

    @Autowired VideoAssetRepository assets;
    @Autowired EncodingVideoStatusLookup lookup;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void addCatalog() {
        for (int id : List.of(10, 20, 30)) {
            addEpisode(uuid(id));
        }
    }

    private void addEpisode(UUID episode) {
        UUID title = titleId(episode);
        jdbc.update("""
                insert into titles (id,title,slug,description,status,created_at,updated_at,content_type)
                values (?, 'Fixture', ?, 'Fixture', 'PUBLISHED', now(), now(), 'MOVIE')
                """, title, "fixture-" + title);
        jdbc.update("""
                insert into episodes (id,title_id,ordinal,display_label,status,created_at,updated_at)
                values (?, ?, 1, '1', 'PUBLISHED', now(), now())
                """, episode, title);
    }

    private static UUID titleId(UUID episode) {
        return new UUID(episode.getMostSignificantBits() ^ 1, episode.getLeastSignificantBits());
    }

    @Test
    void noAssetsProducesNullAndEmptyInputRunsNoSql() {
        assertThat(lookup.videoStatus(titleId(uuid(10)))).isNull();
        RecordingStatementInspector.clear();

        assertThat(lookup.videoStatuses(List.of())).isEmpty();
        assertThat(RecordingStatementInspector.statements()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(VideoAssetStatus.class)
    void aSingleAssetReturnsItsStatus(VideoAssetStatus status) {
        assets.saveAndFlush(asset(uuid(1), uuid(10), status, T0));

        assertThat(lookup.videoStatus(titleId(uuid(10)))).isEqualTo(status.name());
        assertAgreesWithRepository(uuid(10));
    }

    @ParameterizedTest
    @EnumSource(value = VideoAssetStatus.class, names = {"UPLOADED", "QUEUED", "PROCESSING", "FAILED"})
    void olderReadyKeepsAvailabilityWhileANewerReplacementIsNotReady(VideoAssetStatus replacement) {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(2), uuid(10), replacement, T0.plusSeconds(1))));

        assertThat(lookup.videoStatus(titleId(uuid(10)))).isEqualTo("READY");
        assertAgreesWithRepository(uuid(10));
    }

    @Test
    void aNewerReadyWinsOverOlderFailure() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.READY, T0.plusSeconds(1))));

        assertThat(lookup.videoStatus(titleId(uuid(10)))).isEqualTo("READY");
        assertAgreesWithRepository(uuid(10));
    }

    @Test
    void withoutReadyUsesTheNewestStatusAndAllFailedStaysFailed() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.QUEUED, T0.plusSeconds(1)),
                asset(uuid(3), uuid(20), VideoAssetStatus.FAILED, T0),
                asset(uuid(4), uuid(20), VideoAssetStatus.FAILED, T0.plusSeconds(1))));

        assertThat(lookup.videoStatuses(List.of(titleId(uuid(10)), titleId(uuid(20)), titleId(uuid(30)))))
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        titleId(uuid(10)), "QUEUED", titleId(uuid(20)), "FAILED"));
        assertAgreesWithRepository(uuid(10));
        assertAgreesWithRepository(uuid(20));
        assertAgreesWithRepository(uuid(30));
    }

    @Test
    void equalTimestampsUseUnsignedUuidOrderWhenNoReadyExists() {
        UUID lower = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID higher = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertThat(higher.compareTo(lower)).isNegative();
        assets.saveAllAndFlush(List.of(asset(lower, uuid(10), VideoAssetStatus.QUEUED, T0),
                asset(higher, uuid(10), VideoAssetStatus.FAILED, T0)));

        assertThat(lookup.videoStatus(titleId(uuid(10)))).isEqualTo("FAILED");
        assertAgreesWithRepository(uuid(10));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    void oneStatementResolvesTheWholePageRegardlessOfSize(int count) {
        List<UUID> movieIds = new ArrayList<>();
        List<VideoAsset> history = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            UUID movieId = uuid(1000 + index);
            addEpisode(movieId);
            movieIds.add(movieId);
            history.add(asset(uuid(index), movieId, VideoAssetStatus.READY, T0));
            history.add(asset(uuid(1000 + index), movieId, VideoAssetStatus.FAILED, T0.plusSeconds(1)));
        }
        assets.saveAllAndFlush(history);
        RecordingStatementInspector.clear();

        var statuses = lookup.videoStatuses(movieIds.stream().map(EncodingVideoStatusLookupTest::titleId).toList());

        assertThat(statuses).hasSize(count);
        assertThat(statuses.values()).containsOnly("READY");
        assertThat(RecordingStatementInspector.statements()).hasSize(1);
        assertThat(RecordingStatementInspector.statements().getFirst()).contains("current_episode_assets");
        movieIds.forEach(this::assertAgreesWithRepository);
    }

    private void assertAgreesWithRepository(UUID movieId) {
        String expected = assets.findCurrentReadyByEpisodeId(movieId).map(asset -> "READY")
                .orElseGet(() -> assets.findNewestByEpisodeId(movieId).map(asset -> asset.getStatus().name()).orElse(null));
        assertThat(lookup.videoStatus(titleId(movieId))).isEqualTo(expected);
    }
}
