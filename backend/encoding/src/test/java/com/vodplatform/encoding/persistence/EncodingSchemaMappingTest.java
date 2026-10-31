package com.vodplatform.encoding.persistence;

import static com.vodplatform.encoding.persistence.EncodingFixtures.T0;
import static com.vodplatform.encoding.persistence.EncodingFixtures.asset;
import static com.vodplatform.encoding.persistence.EncodingFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.vodplatform.encoding.config.EncodingConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

/** H2 mapping checks; the PostgreSQL suite validates the real Flyway V1-V5 schema. */
@DataJpaTest
@ContextConfiguration(classes = EncodingConfiguration.class)
class EncodingSchemaMappingTest {

    @Autowired EntityManager entityManager;
    @Autowired VideoAssetRepository assets;
    @Autowired EncodingJobRepository jobs;

    @Test
    void registersExactlyTheEncodingEntities() {
        Set<Class<?>> types = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType).collect(Collectors.toSet());
        assertThat(types).containsExactlyInAnyOrder(VideoAsset.class, EncodingJob.class);
    }

    @Test
    void columnsMatchTheFrozenSchemaIncludingNullablePublication() {
        List<Object[]> rows = rows("""
                select lower(table_name), lower(column_name), data_type, character_maximum_length, is_nullable
                from information_schema.columns where table_schema = 'PUBLIC'
                """);
        Set<String> columns = rows.stream().map(EncodingSchemaMappingTest::describe).collect(Collectors.toSet());
        assertThat(columns).containsExactlyInAnyOrder(
                "video_assets.id uuid NOT NULL", "video_assets.movie_id uuid NOT NULL",
                "video_assets.raw_bucket varchar(100) NOT NULL", "video_assets.raw_object_key varchar(512) NOT NULL",
                "video_assets.hls_bucket varchar(100) NULL", "video_assets.hls_master_object_key varchar(512) NULL",
                "video_assets.status enum NOT NULL", "video_assets.duration_seconds int NULL",
                "video_assets.width int NULL", "video_assets.height int NULL", "video_assets.codec varchar(100) NULL",
                "video_assets.bitrate int NULL", "video_assets.failure_reason text NULL",
                "video_assets.created_at timestamptz NOT NULL", "video_assets.updated_at timestamptz NOT NULL",
                "encoding_jobs.id uuid NOT NULL", "encoding_jobs.video_asset_id uuid NOT NULL",
                "encoding_jobs.status enum NOT NULL", "encoding_jobs.attempt int NOT NULL",
                "encoding_jobs.error_message text NULL", "encoding_jobs.queued_at timestamptz NOT NULL",
                "encoding_jobs.published_at timestamptz NULL", "encoding_jobs.started_at timestamptz NULL",
                "encoding_jobs.finished_at timestamptz NULL");
        assertThat(entityManager.createNativeQuery("""
                select column_default from information_schema.columns
                where table_schema = 'PUBLIC' and table_name = 'ENCODING_JOBS' and column_name = 'PUBLISHED_AT'
                """).getSingleResult()).isNull();
    }

    @Test
    void primaryKeysUniqueNamesAndIndexesMatchTheMapping() {
        assertThat(rows("""
                select lower(tc.table_name), lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_schema = tc.constraint_schema and kcu.constraint_name = tc.constraint_name
                where tc.table_schema = 'PUBLIC' and tc.constraint_type = 'PRIMARY KEY'
                """).stream().map(row -> row[0] + "." + row[1]))
                .containsExactlyInAnyOrder("video_assets.id", "encoding_jobs.id");
        assertThat(rows("""
                select lower(tc.constraint_name), lower(kcu.column_name)
                from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_schema = tc.constraint_schema and kcu.constraint_name = tc.constraint_name
                where tc.table_schema = 'PUBLIC' and tc.constraint_type = 'UNIQUE'
                """).stream().map(row -> row[0] + "." + row[1]))
                .containsExactlyInAnyOrder("ux_video_assets_raw_object.raw_bucket", "ux_video_assets_raw_object.raw_object_key",
                        "ux_video_assets_hls_master.hls_bucket", "ux_video_assets_hls_master.hls_master_object_key",
                        "ux_encoding_jobs_asset_attempt.video_asset_id", "ux_encoding_jobs_asset_attempt.attempt");
        assertThat(rows("select lower(index_name), index_type_name from information_schema.indexes where table_schema = 'PUBLIC'")
                .stream().map(row -> row[0].toString())).contains("ix_video_assets_movie_id", "ix_video_assets_status",
                        "ix_encoding_jobs_video_asset_id", "ix_encoding_jobs_status")
                .doesNotContain(EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT);
        assertThat(rows("""
                select index_name from information_schema.index_columns
                where table_schema = 'PUBLIC' and table_name = 'VIDEO_ASSETS' and column_name = 'MOVIE_ID'
                  and is_unique
                """)).isEmpty();
    }

    @Test
    void separateEnumsMatchV1AndInFlightIncludesTheReservedState() {
        assertThat(Arrays.stream(VideoAssetStatus.values()).map(Enum::name))
                .containsExactly("UPLOADED", "QUEUED", "PROCESSING", "READY", "FAILED");
        assertThat(Arrays.stream(EncodingJobStatus.values()).map(Enum::name))
                .containsExactly("QUEUED", "PROCESSING", "READY", "FAILED");
        assertThat(VideoAssetStatus.IN_FLIGHT)
                .containsExactlyInAnyOrder(VideoAssetStatus.UPLOADED, VideoAssetStatus.QUEUED, VideoAssetStatus.PROCESSING);
    }

    @Test
    void duplicateRawObjectMatchesItsOwnName() {
        assets.saveAndFlush(asset(uuid(1), uuid(10), VideoAssetStatus.FAILED, T0, "same-key"));
        Throwable failure = catchThrowable(() -> assets.saveAndFlush(asset(uuid(2), uuid(20), VideoAssetStatus.FAILED, T0, "same-key")));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_RAW_OBJECT)).isTrue();
        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.VIDEO_ASSETS_MOVIE_IN_FLIGHT)).isFalse();
    }

    @Test
    void duplicateAssetAttemptMatchesItsOwnName() {
        VideoAsset asset = asset(uuid(1), uuid(10), VideoAssetStatus.QUEUED, T0);
        EncodingJob first = EncodingJob.firstAttempt(asset, uuid(101));
        EncodingJob duplicate = EncodingJob.firstAttempt(asset, uuid(102));
        assets.saveAndFlush(asset);
        jobs.saveAndFlush(first);

        Throwable failure = catchThrowable(() -> jobs.saveAndFlush(duplicate));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(EncodingConstraints.isViolationOf(failure, EncodingConstraints.ENCODING_JOBS_ASSET_ATTEMPT)).isTrue();
    }

    @Test
    void oneMovieCanRetainReadyFailedAndQueuedHistory() {
        assets.saveAllAndFlush(List.of(asset(uuid(1), uuid(10), VideoAssetStatus.READY, T0),
                asset(uuid(2), uuid(10), VideoAssetStatus.FAILED, T0.plusSeconds(1)),
                asset(uuid(3), uuid(10), VideoAssetStatus.QUEUED, T0.plusSeconds(2))));

        assertThat(assets.count()).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql) {
        return entityManager.createNativeQuery(sql).getResultList();
    }

    private static String describe(Object[] row) {
        String type = switch (row[2].toString()) {
            case "UUID" -> "uuid";
            case "INTEGER" -> "int";
            case "TIMESTAMP WITH TIME ZONE" -> "timestamptz";
            case "CHARACTER VARYING" -> "varchar(" + row[3] + ")";
            case "CHARACTER LARGE OBJECT" -> "text";
            case "ENUM" -> "enum";
            default -> "unexpected " + row[2];
        };
        return row[0] + "." + row[1] + " " + type + ("YES".equals(row[4]) ? " NULL" : " NOT NULL");
    }
}
