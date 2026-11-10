package com.vodplatform.worker.status;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.WorkerFailureCode;
import com.vodplatform.worker.status.WorkerJobStatus.Attempt;
import com.vodplatform.worker.status.WorkerJobStatus.ClaimOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.FinishOutcome;
import com.vodplatform.worker.status.WorkerJobStatus.HlsObject;
import com.vodplatform.worker.status.WorkerJobStatus.Ready;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;

@EnabledIfEnvironmentVariable(named = "WORKER_DEPENDENCY_SMOKE", matches = "true")
@Timeout(30)
class WorkerJdbcPostgresTest {
    @Test
    void connectsAsTheScopedWorkerWithABoundedStatement() throws SQLException {
        try (var context = new AnnotationConfigApplicationContext(WorkerJdbcConfiguration.class)) {
            assertEquals(Status.UP, context.getBean("workerDatabase", HealthIndicator.class).health().getStatus());
            try (var connection = context.getBean(DataSource.class).getConnection();
                    var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT current_user")) {
                    assertTrue(rows.next());
                    assertTrue(System.getenv("WORKER_TEST_POSTGRES_USER").equals(rows.getString(1)),
                            "JDBC must connect with the worker role");
                }
                try (var rows = statement.executeQuery("SHOW statement_timeout")) {
                    assertTrue(rows.next());
                    assertEquals("700ms", rows.getString(1));
                }
                try (var rows = statement.executeQuery("SELECT id FROM encoding_jobs LIMIT 1")) {
                    assertEquals(1, rows.getMetaData().getColumnCount());
                }
                assertEquals(0, statement.executeUpdate("UPDATE video_assets SET status = 'PROCESSING' WHERE false"));

                SQLException denied = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT id FROM users"));
                assertEquals("42501", denied.getSQLState());

                SQLException cancelled = assertThrows(SQLException.class,
                        () -> statement.executeQuery("SELECT pg_sleep(5)"));
                assertEquals("57014", cancelled.getSQLState(), "server must cancel at statement_timeout");
                try (var rows = statement.executeQuery("SELECT 1")) {
                    assertTrue(rows.next(), "statement timeout must leave the connection usable");
                }
            }
        }
    }

    @Test
    void claimsAndFinishesThePairWithTrustedKeysTimestampsAndMetadata() throws SQLException {
        try (var fixture = fixture("QUEUED", "QUEUED")) {
            var service = new WorkerJobStatus(fixture.worker());
            var claim = service.claim(fixture.attempt());
            assertEquals(ClaimOutcome.ACQUIRED, claim.outcome());
            assertEquals(new WorkerJobStatus.RawObject("vod-raw", fixture.rawKey()), claim.raw());
            var processing = fixture.snapshot();
            assertEquals("PROCESSING", processing.assetState());
            assertEquals("PROCESSING", processing.jobState());
            assertNotNull(processing.started());
            assertNull(processing.finished());

            var expected = fixture.ready();
            assertEquals(FinishOutcome.COMPLETED, service.finish(fixture.attempt(), expected).outcome());
            var ready = fixture.snapshot();
            assertEquals("READY", ready.assetState());
            assertEquals("READY", ready.jobState());
            assertEquals(expected.hls().masterKey(), ready.masterKey());
            assertEquals(expected.hls().bucket(), ready.hlsBucket());
            assertEquals(2, ready.duration());
            assertEquals(1920, ready.width());
            assertEquals(1080, ready.height());
            assertEquals("h264", ready.codec());
            assertNull(ready.bitrate());
            assertEquals(processing.started(), ready.started());
            assertNotNull(ready.finished());
            assertNull(ready.failure());
            assertNull(ready.error());
            assertEquals(ClaimOutcome.STALE, service.claim(fixture.attempt()).outcome());
        }
    }

    @Test
    void invalidIdsAndInconsistentPairsDoNotClaimOrFinish() throws SQLException {
        try (var fixture = fixture("QUEUED", "PROCESSING")) {
            var service = new WorkerJobStatus(fixture.worker());
            var attempt = fixture.attempt();
            assertEquals(ClaimOutcome.INVALID,
                    service.claim(new Attempt(attempt.jobId(), attempt.assetId(), 2)).outcome());
            assertEquals(ClaimOutcome.UNRESOLVED, service.claim(attempt).outcome());
            assertEquals(ClaimOutcome.UNRESOLVED, service.readClaimAfterUnknownCommit(attempt).outcome());
            assertEquals(FinishOutcome.UNRESOLVED, service.finish(attempt, fixture.ready()).outcome());
            assertEquals("QUEUED", fixture.snapshot().assetState());
            assertEquals("PROCESSING", fixture.snapshot().jobState());
        }
    }

    @Test
    void rollsBackTheRealAssetUpdateWhenTheSecondUpdateAffectsZeroRows() throws SQLException {
        try (var fixture = fixture("QUEUED", "QUEUED");
                var connection = fixture.worker().getConnection()) {
            var fault = mock(Connection.class, delegatesTo(connection));
            doAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.startsWith("UPDATE encoding_jobs")) {
                    return mock(PreparedStatement.class);
                }
                return connection.prepareStatement(sql);
            }).when(fault).prepareStatement(org.mockito.ArgumentMatchers.anyString());
            var source = mock(DataSource.class);
            when(source.getConnection()).thenReturn(fault);

            assertThrows(SQLException.class, () -> new WorkerJobStatus(source).claim(fixture.attempt()));
            assertEquals("QUEUED", fixture.snapshot().assetState());
            assertEquals("QUEUED", fixture.snapshot().jobState());
        }
    }

    @Test
    void onlyOneConcurrentClaimOwnsTheAttempt() throws Exception {
        try (var fixture = fixture("QUEUED", "QUEUED");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new WorkerJobStatus(fixture.worker());
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return service.claim(fixture.attempt()).outcome(); });
            var b = executor.submit(() -> { start.await(); return service.claim(fixture.attempt()).outcome(); });
            start.countDown();
            assertEquals(Set.of(ClaimOutcome.ACQUIRED, ClaimOutcome.UNRESOLVED),
                    Set.of(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS)));
        }
    }

    @Test
    void readsBackTheCommittedClaimAndContinuesToReadyAfterTheClientLosesCommit() throws Exception {
        try (var fixture = fixture("QUEUED", "QUEUED");
                var proxy = new CommitResponseDrop(fixture.worker().getPortNumbers()[0])) {
            var faulty = new WorkerJobStatus(proxy.dataSource(fixture.worker()));
            var service = new WorkerJobStatus(fixture.worker());
            assertThrows(WorkerJobStatus.CommitOutcomeUnknownException.class, () -> faulty.claim(fixture.attempt()));
            assertTrue(proxy.droppedCommit(), "PostgreSQL must complete COMMIT before the proxy cuts the response");
            var started = fixture.snapshot().started();
            assertNotNull(started);
            assertEquals(ClaimOutcome.UNRESOLVED, service.claim(fixture.attempt()).outcome());
            var owned = service.readClaimAfterUnknownCommit(fixture.attempt());
            assertEquals(ClaimOutcome.ACQUIRED, owned.outcome());
            assertEquals(fixture.rawKey(), owned.raw().objectKey());

            // The fixture supplies the media result; Issue 5.7 verifies the actual processor and broker ACK.
            assertEquals(FinishOutcome.COMPLETED, service.finish(fixture.attempt(), fixture.ready()).outcome());
            assertEquals("READY", fixture.snapshot().assetState());
            assertEquals(started, fixture.snapshot().started());
        }
    }

    @Test
    void aCommittedReadyWithALostResponseKeepsItsStoredHlsReference() throws Exception {
        try (var fixture = fixture("QUEUED", "QUEUED");
                var proxy = new CommitResponseDrop(fixture.worker().getPortNumbers()[0])) {
            var service = new WorkerJobStatus(fixture.worker());
            assertEquals(ClaimOutcome.ACQUIRED, service.claim(fixture.attempt()).outcome());
            assertEquals(FinishOutcome.RETRY, service.readFinishAfterUnknownCommit(fixture.attempt(), fixture.ready())
                    .outcome());
            var faulty = new WorkerJobStatus(proxy.dataSource(fixture.worker()));
            assertThrows(WorkerJobStatus.CommitOutcomeUnknownException.class,
                    () -> faulty.finish(fixture.attempt(), fixture.ready()));
            assertTrue(proxy.droppedCommit(), "PostgreSQL must complete COMMIT before the proxy cuts the response");

            var completed = service.readFinishAfterUnknownCommit(fixture.attempt(), fixture.ready());
            assertEquals(FinishOutcome.COMPLETED, completed.outcome());
            assertEquals(fixture.ready().hls(), completed.referencedHls());
            var mismatched = new Ready(new HlsObject("vod-hls", "another/master.m3u8"), fixture.ready().metadata());
            var lost = service.finish(fixture.attempt(), mismatched);
            assertEquals(FinishOutcome.LOST, lost.outcome());
            assertEquals(fixture.ready().hls(), lost.referencedHls());
            assertEquals(fixture.ready().hls().masterKey(), fixture.snapshot().masterKey());
        }
    }

    @Test
    void persistsOnlyTheRecoveryFailureCodeAndRejectsALateFinishAfterANewerAttempt() throws SQLException {
        try (var fixture = fixture("QUEUED", "QUEUED")) {
            var service = new WorkerJobStatus(fixture.worker());
            assertEquals(ClaimOutcome.ACQUIRED, service.claim(fixture.attempt()).outcome());
            var failed = new WorkerJobStatus.Failed(WorkerFailureCode.WORKER_INTERRUPTED);
            assertEquals(FinishOutcome.COMPLETED, service.finish(fixture.attempt(), failed).outcome());
            var recovered = fixture.snapshot();
            assertEquals("FAILED", recovered.assetState());
            assertEquals("FAILED", recovered.jobState());
            assertEquals("WORKER_INTERRUPTED", recovered.failure());
            assertEquals("WORKER_INTERRUPTED", recovered.error());
            assertNotNull(recovered.finished());

            try (var seed = fixture.backend().getConnection()) {
                update(seed, "UPDATE video_assets SET status = 'QUEUED' WHERE id = ?", fixture.attempt().assetId());
                update(seed, """
                        INSERT INTO encoding_jobs(id, video_asset_id, status, attempt, queued_at)
                        VALUES (?, ?, 'QUEUED', 2, CURRENT_TIMESTAMP)
                        """, UUID.randomUUID(), fixture.attempt().assetId());
            }
            assertEquals(ClaimOutcome.STALE, service.claim(fixture.attempt()).outcome());
            assertEquals(FinishOutcome.LOST, service.finish(fixture.attempt(), fixture.ready()).outcome());
            assertEquals("QUEUED", fixture.snapshot().assetState());
            assertEquals("FAILED", fixture.snapshot().jobState());
            assertNull(fixture.snapshot().masterKey());
        }
    }

    private static Fixture fixture(String assetStatus, String jobStatus) throws SQLException {
        var worker = (PGSimpleDataSource) new WorkerJdbcConfiguration().workerDataSource(new StandardEnvironment());
        assertArrayEquals(new String[] {"127.0.0.1"}, worker.getServerNames());
        assertEquals("s5w6_fixture", worker.getDatabaseName());
        assertTrue("s5w6_worker".equals(worker.getUser()), "Expected disposable worker role");
        assertTrue("s5w6_backend".equals(System.getenv("POSTGRES_BACKEND_USER")), "Expected disposable seed role");
        var backend = new PGSimpleDataSource();
        backend.setServerNames(worker.getServerNames());
        backend.setPortNumbers(worker.getPortNumbers());
        backend.setDatabaseName(worker.getDatabaseName());
        backend.setUser(System.getenv("POSTGRES_BACKEND_USER"));
        backend.setPassword(System.getenv("POSTGRES_BACKEND_PASSWORD"));
        backend.setConnectTimeout(5);
        backend.setSocketTimeout(8);
        UUID movie = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        String raw = "fixtures/" + asset + ".mp4";
        try (var connection = backend.getConnection()) {
            connection.setAutoCommit(false);
            update(connection, """
                    INSERT INTO movies(id, title, slug, description, status, created_at, updated_at)
                    VALUES (?, 'Fixture', ?, 'Fixture', 'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, movie, "fixture-" + movie);
            update(connection, """
                    INSERT INTO video_assets(id, movie_id, raw_bucket, raw_object_key, status, created_at, updated_at)
                    VALUES (?, ?, 'vod-raw', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, asset, movie, raw, assetStatus);
            update(connection, """
                    INSERT INTO encoding_jobs(id, video_asset_id, status, attempt, queued_at)
                    VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)
                    """, job, asset, jobStatus);
            connection.commit();
        }
        return new Fixture(worker, backend, movie, new Attempt(job, asset, 1), raw);
    }

    private static void update(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private record Fixture(PGSimpleDataSource worker, DataSource backend, UUID movie, Attempt attempt, String rawKey)
            implements AutoCloseable {
        Ready ready() {
            return new Ready(new HlsObject("vod-hls", "video-assets/" + attempt.assetId() + "/attempt-1/master.m3u8"),
                    new MediaMetadata(Duration.ofMillis(1500), 1920, 1080, 0, false, "h264", null));
        }

        Snapshot snapshot() throws SQLException {
            try (var connection = worker.getConnection(); var statement = connection.prepareStatement("""
                    SELECT a.status, j.status, a.hls_bucket, a.hls_master_object_key, a.duration_seconds,
                           a.width, a.height, a.codec, a.bitrate, a.failure_reason, j.error_message,
                           j.started_at, j.finished_at
                    FROM video_assets a JOIN encoding_jobs j ON j.video_asset_id = a.id
                    WHERE a.id = ? AND j.id = ?
                    """)) {
                statement.setObject(1, attempt.assetId());
                statement.setObject(2, attempt.jobId());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return new Snapshot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            rows.getInt(5), rows.getInt(6), rows.getInt(7), rows.getString(8),
                            rows.getObject(9, Integer.class), rows.getString(10), rows.getString(11),
                            rows.getTimestamp(12), rows.getTimestamp(13));
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try (var connection = backend.getConnection()) {
                update(connection, "DELETE FROM movies WHERE id = ?", movie);
            }
        }
    }

    private record Snapshot(String assetState, String jobState, String hlsBucket, String masterKey, int duration,
                            int width, int height, String codec, Integer bitrate, String failure, String error,
                            Timestamp started, Timestamp finished) {}
}
