package com.vodplatform.worker.queue;

import static com.vodplatform.worker.testsupport.MediaFixture.AUDIO_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.COVER_ART_ONLY_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.EBML_UNDER_MP4_KEY;
import static com.vodplatform.worker.testsupport.MediaFixture.H264_AAC_MP4;
import static com.vodplatform.worker.testsupport.MediaFixture.TRUNCATED_FASTSTART_MP4;
import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.vodplatform.worker.queue.messaging.EncodingJobMessage;
import com.vodplatform.worker.queue.messaging.EncodingJobMessageCodec;
import com.vodplatform.worker.testsupport.HlsOutputCheck;
import com.vodplatform.worker.testsupport.HlsPlaylist;
import com.vodplatform.worker.testsupport.MediaFixtures;
import com.vodplatform.worker.testsupport.MediaTools;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Runs only on a fresh s5w7 orchestration smoke project, with the real worker runtime tools. */
@EnabledIfEnvironmentVariable(named = "WORKER_ORCHESTRATION_TEST", matches = "true")
@Timeout(240)
class WorkerOrchestrationIntegrationTests {
    private static final String DATABASE = "s5w7_fixture";
    private static final String QUEUE = "s5w7-orchestration-queue";
    private static final String RAW = "vod-raw";
    private static final String HLS = "vod-hls";
    private static final String MINIO = "http://minio:9000";
    private static final long MIB = 1024L * 1024L;

    @TempDir Path sandbox;
    private Path scratch;
    private Path ffmpeg;
    private Path encodeCalls;
    private MediaFixtures media;
    private PGSimpleDataSource database;
    private Connection broker;
    private Channel admin;
    private Connection publisher;
    private Channel publishChannel;
    private MinioClient backendStorage;
    private MinioClient workerStorage;
    private boolean queuesDeclared;

    @BeforeEach
    void createFixtureClients() throws Exception {
        // Only the launcher provisions these roles, and fixture hosts are fixed Compose service names.
        queuesDeclared = false;
        assertThat(required("POSTGRES_BACKEND_USER").equals("s5w7_backend")).isTrue();
        assertThat(required("POSTGRES_WORKER_USER").equals("s5w7_worker")).isTrue();
        database = new PGSimpleDataSource();
        database.setServerNames(new String[]{"postgres"});
        database.setPortNumbers(new int[]{5432});
        database.setDatabaseName(DATABASE);
        database.setUser(required("POSTGRES_BACKEND_USER"));
        database.setPassword(required("POSTGRES_BACKEND_PASSWORD"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(5);
        broker = connectRabbit("RABBITMQ");
        admin = broker.createChannel();
        publisher = connectRabbit("RABBITMQ_BACKEND");
        publishChannel = publisher.createChannel();
        publishChannel.confirmSelect();
        declareQueue();
        queuesDeclared = true;
        assertThat(ready(QUEUE)).isZero();
        backendStorage = storage("MINIO_BACKEND");
        workerStorage = storage("MINIO_WORKER");
        scratch = Files.createDirectory(sandbox.resolve("worker-scratch"));
        encodeCalls = sandbox.resolve("encode-calls");
        ffmpeg = script("count-ffmpeg", "printf 'encode\\n' >> " + quote(encodeCalls)
                + "\nexec /usr/bin/ffmpeg \"$@\"\n");
        media = new MediaFixtures(MediaTools.fromEnvironment(), sandbox.resolve("generated"));
    }

    @AfterEach
    void closeFixtureClients() throws Exception {
        try (var brokerConnection = broker; var publisherConnection = publisher;
                var backendClient = backendStorage; var workerClient = workerStorage) {
            if (queuesDeclared && admin.isOpen()) {
                // A failing assertion may leave a delivery in these disposable fixture queues.
                admin.queuePurge(QUEUE);
                admin.queuePurge(QUEUE + ".dlq");
            }
        }
    }

    @Test
    void realDeliveryHoldsClaimAndFinishOutagesThenPersistsAndAcknowledgesExactlyOnce() throws Exception {
        Job first = seed(media.path(H264_AAC_MP4), 2);
        Job stale = insertAttempt(first, 1, "FAILED");
        Job rejected = seed(media.path(EBML_UNDER_MP4_KEY), 1);
        Job finish = seed(media.path(H264_AAC_MP4), 1);
        Job waiting = seed(media.path(AUDIO_ONLY_MP4), 1);
        int initialDlq = ready(QUEUE + ".dlq");

        try (var faults = new OrchestrationPostgresFaults();
                var worker = startWorker(settings("ffprobe", reserve()), faults)) {
            try {
                await("one real worker consumer", () -> consumers() == 1);
                faults.block();
                publish(first);
                await("worker retried the unavailable claim", () -> faults.refusedConnections() >= 2);
                publish(rejected);
                await("prefetch holds the second delivery behind a DB outage", () -> ready(QUEUE) == 1);
                assertThat(state(first).job()).isEqualTo("QUEUED");
                assertThat(calls()).isZero();
                assertThat(ready(QUEUE + ".dlq")).isEqualTo(initialDlq);

                faults.cutCommit(OrchestrationPostgresFaults.Cut.AFTER_COMMIT, 0);
                faults.resume();
                await("server committed claim but JDBC lost the response", () -> faults.droppedCommits() == 1);
                await("claim transaction committed before the reply was cut", () -> state(first).processing());
                Timestamp originalClaim = state(first).started();
                assertThat(ready(QUEUE)).isEqualTo(1);
                assertThat(calls()).isZero();
                faults.resume();
                await("owned claim continues to READY and wrong-family bytes become FAILED",
                        () -> state(first).ready() && state(rejected).failed("PROBE_FAILED") && ready(QUEUE) == 0);
                assertThat(state(first).started()).isEqualTo(originalClaim);
                assertThat(calls()).isEqualTo(1);
                assertStoredHls(first);
                assertRawRetained(first);

                faults.cutCommit(OrchestrationPostgresFaults.Cut.BEFORE_COMMIT, 1);
                publish(finish);
                await("finish transaction loses its connection before COMMIT", () -> faults.droppedCommits() == 2);
                int refused = faults.refusedConnections();
                publish(waiting);
                await("finish DB retries keep the delivery and prefetch slot",
                        () -> faults.refusedConnections() > refused && ready(QUEUE) == 1);
                assertThat(state(finish).processing()).isTrue();
                assertThat(state(finish).master()).isNull();
                assertThat(calls()).isEqualTo(2);
                assertThat(readHls(finish.prefix() + "master.m3u8")).isNotEmpty();
                assertThat(ready(QUEUE + ".dlq")).isEqualTo(initialDlq);
                faults.resume();
                await("finish commits before the next delivery can complete",
                        () -> state(finish).ready() && state(waiting).failed("UNSUPPORTED_MEDIA") && ready(QUEUE) == 0);
                assertThat(calls()).isEqualTo(2);
                assertStoredHls(finish);

                publish(first);
                publish(stale);
                publish(new Job(first.job(), UUID.randomUUID(), first.attempt(), first.rawKey()));
                await("READY and stale are skipped and the DB-mismatched delivery dead-letters",
                        () -> ready(QUEUE) == 0 && ready(QUEUE + ".dlq") == initialDlq + 1);
                assertThat(state(first).ready()).isTrue();
                assertThat(state(first).started()).isEqualTo(originalClaim);
                assertThat(state(stale).job()).isEqualTo("FAILED");
                assertThat(calls()).isEqualTo(2);
                assertStoredHls(first);
                assertNoJobDirectories();
            } finally {
                faults.resume();
            }
        }
        assertNothingRequeuedAfterClosingWorker();
    }

    @Test
    void abruptWorkerExitRequeuesAndRestartRecoversBeforeEncodingAgain() throws Exception {
        Job interrupted = seed(media.path(H264_AAC_MP4), 1);
        String attemptTen = "video-assets/" + interrupted.asset() + "/attempt-10/master.m3u8";
        String other = "video-assets/" + UUID.randomUUID() + "/attempt-1/master.m3u8";
        seedHls(interrupted.prefix() + "master.m3u8");
        seedHls(attemptTen);
        seedHls(other);
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("preserve"), "keep");
        Path gate = sandbox.resolve("probe-reached");
        Path stallingProbe = script("stall-ffprobe", "printf reached > " + quote(gate) + "\nexec /bin/sleep 60\n");
        int initialDlq = ready(QUEUE + ".dlq");

        Process child = startChild(settings(stallingProbe.toString(), reserve()));
        try {
            await("child worker completed startup and subscribed", Duration.ofSeconds(90), () -> {
                assertThat(child.isAlive()).as("fixture worker has not exited during startup").isTrue();
                return consumers() == 1;
            });
            publish(interrupted);
            await("child is processing and has downloaded its raw input", () -> {
                assertThat(child.isAlive()).as("fixture worker has not exited before processing").isTrue();
                return Files.exists(gate) && state(interrupted).processing() && ready(QUEUE) == 0;
            });
            try (var entries = Files.list(scratch)) {
                Path jobDir = entries.filter(path -> path.getFileName().toString().startsWith("job-"))
                        .findFirst().orElseThrow();
                Files.createSymbolicLink(jobDir.resolve("outside-link"), outside);
            }
        } catch (AssertionError failure) {
            String log = Files.readString(sandbox.resolve("worker-child.log"));
            throw new AssertionError("Child startup reached Spring="
                    + log.contains("Started VodWorkerApplication") + ", recovery="
                    + log.contains("Worker database recovery completed") + ", broker="
                    + log.contains("Created new connection"), failure);
        } finally {
            killChild(child);
        }
        await("crash requeued the unacknowledged delivery", () -> ready(QUEUE) == 1 && consumers() == 0);
        assertThat(state(interrupted).processing()).isTrue();
        assertThat(readHls(interrupted.prefix() + "master.m3u8")).isNotEmpty();

        try (var worker = startWorker(settings("ffprobe", reserve()), null)) {
            await("restart recovery persists the orphan outcome and acknowledges its requeued delivery",
                    () -> state(interrupted).failed("WORKER_INTERRUPTED") && ready(QUEUE) == 0 && consumers() == 1);
            assertNoJobDirectories();
            assertThat(Files.readString(sentinel)).isEqualTo("keep");
            assertMissingHls(interrupted.prefix() + "master.m3u8");
            assertThat(readHls(attemptTen)).isNotEmpty();
            assertThat(readHls(other)).isNotEmpty();
            assertRawRetained(interrupted);
            assertThat(calls()).isZero();
            assertThat(ready(QUEUE + ".dlq")).isEqualTo(initialDlq);
        }
        assertNothingRequeuedAfterClosingWorker();
    }

    @Test
    void rejectsInsufficientDiskBeforeMediaWorkAndRetainsRaw() throws Exception {
        Job job = seed(media.path(H264_AAC_MP4), 1);
        long impossibleReserve = Files.getFileStore(scratch).getTotalSpace() / MIB + 1;
        int initialDlq = ready(QUEUE + ".dlq");
        try (var worker = startWorker(settings("ffprobe", impossibleReserve), null)) {
            publish(job);
            await("disk check persists FAILED and acknowledges the delivery",
                    () -> state(job).failed("INSUFFICIENT_DISK") && ready(QUEUE) == 0);
            assertRawRetained(job);
            assertThat(calls()).isZero();
            assertNoJobDirectories();
            assertMissingHls(job.prefix() + "master.m3u8");
            assertThat(ready(QUEUE + ".dlq")).isEqualTo(initialDlq);
        }
        assertNothingRequeuedAfterClosingWorker();
    }

    @Test
    void truncatedFaststartAndCoverArtAreFailedWithoutExposingPartialHls() throws Exception {
        Job truncated = seed(media.path(TRUNCATED_FASTSTART_MP4), 1);
        Job cover = seed(media.path(COVER_ART_ONLY_MP4), 1);
        seedHls(truncated.prefix() + "master.m3u8");
        int initialDlq = ready(QUEUE + ".dlq");
        try (var worker = startWorker(settings("ffprobe", reserve()), null)) {
            publish(truncated);
            publish(cover);
            await("output validation and stream selection reject generated negatives",
                    () -> state(truncated).failed("ENCODE_FAILED") && state(cover).failed("UNSUPPORTED_MEDIA")
                            && ready(QUEUE) == 0);
            assertThat(calls()).isEqualTo(1);
            assertNoJobDirectories();
            assertMissingHls(truncated.prefix() + "master.m3u8");
            assertMissingHls(cover.prefix() + "master.m3u8");
            assertThat(ready(QUEUE + ".dlq")).isEqualTo(initialDlq);
        }
        assertNothingRequeuedAfterClosingWorker();
    }

    private void declareQueue() throws IOException {
        String dlx = QUEUE + ".dlx";
        String dlq = QUEUE + ".dlq";
        publishChannel.exchangeDeclare(dlx, "direct", true);
        publishChannel.queueDeclare(dlq, true, false, false, null);
        publishChannel.queueDeclare(QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", dlx, "x-dead-letter-routing-key", dlq));
        publishChannel.queueBind(dlq, dlx, dlq);
    }

    private void publish(Job job) throws Exception {
        var message = new EncodingJobMessage(job.job(), job.asset(), job.attempt());
        var properties = new AMQP.BasicProperties.Builder().contentType("application/json")
                .messageId(job.job().toString()).deliveryMode(2).build();
        publishChannel.basicPublish("", QUEUE, true, properties, new EncodingJobMessageCodec().encode(message));
        publishChannel.waitForConfirmsOrDie(10_000);
    }

    private int ready(String queue) throws IOException {
        return admin.queueDeclarePassive(queue).getMessageCount();
    }

    private int consumers() throws IOException {
        return admin.queueDeclarePassive(QUEUE).getConsumerCount();
    }

    private void assertNothingRequeuedAfterClosingWorker() throws Exception {
        await("worker consumer closed", () -> consumers() == 0);
        assertThat(ready(QUEUE)).as("closing the listener did not requeue a terminal delivery").isZero();
    }

    private Job seed(Path fixture, int attempt) throws Exception {
        UUID movie = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        String rawKey = "s5w7/" + asset + ".mp4";
        try (var stream = Files.newInputStream(fixture)) {
            backendStorage.putObject(PutObjectArgs.builder().bucket(RAW).object(rawKey)
                    .stream(stream, Files.size(fixture), -1L).contentType("video/mp4").build());
        }
        Job job = new Job(UUID.randomUUID(), asset, attempt, rawKey);
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            update(connection, """
                    INSERT INTO movies(id, title, slug, description, status, created_at, updated_at)
                    VALUES (?, 'Fixture', ?, 'Fixture', 'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, movie, "s5w7-" + movie);
            update(connection, """
                    INSERT INTO video_assets(id, movie_id, raw_bucket, raw_object_key, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'QUEUED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, asset, movie, RAW, rawKey);
            insert(connection, job, "QUEUED");
            connection.commit();
        }
        return job;
    }

    private Job insertAttempt(Job current, int attempt, String status) throws Exception {
        Job job = new Job(UUID.randomUUID(), current.asset(), attempt, current.rawKey());
        try (var connection = database.getConnection()) {
            insert(connection, job, status);
        }
        return job;
    }

    private static void insert(java.sql.Connection connection, Job job, String status) throws Exception {
        update(connection, """
                INSERT INTO encoding_jobs(id, video_asset_id, status, attempt, queued_at, published_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, job.job(), job.asset(), status, job.attempt());
    }

    private static void update(java.sql.Connection connection, String sql, Object... args) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private Snapshot state(Job job) throws Exception {
        try (var connection = database.getConnection(); var statement = connection.prepareStatement("""
                SELECT a.status, j.status, a.hls_master_object_key, a.failure_reason, j.error_message,
                       j.started_at, a.duration_seconds, a.width, a.height, a.hls_bucket, j.finished_at
                FROM video_assets a JOIN encoding_jobs j ON j.video_asset_id = a.id
                WHERE a.id = ? AND j.id = ?
                """)) {
            statement.setObject(1, job.asset());
            statement.setObject(2, job.job());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new Snapshot(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getTimestamp(6), rows.getInt(7), rows.getInt(8), rows.getInt(9),
                        rows.getString(10), rows.getTimestamp(11));
            }
        }
    }

    private void assertStoredHls(Job job) throws Exception {
        Snapshot result = state(job);
        assertThat(result.master()).isEqualTo(job.prefix() + "master.m3u8");
        assertThat(result.bucket()).isEqualTo(HLS);
        assertThat(result.duration()).isEqualTo(13);
        assertThat(result.width()).isEqualTo(640);
        assertThat(result.height()).isEqualTo(360);
        assertThat(result.finished()).isNotNull();
        assertThat(result.failure()).isNull();
        assertThat(result.error()).isNull();
        Path hls = Files.createTempDirectory(sandbox, "check-hls-");
        Files.createDirectory(hls.resolve("segments"));
        byte[] master = readHls(result.master());
        Files.write(hls.resolve("master.m3u8"), master);
        var playlist = HlsPlaylist.parse(new String(master, StandardCharsets.UTF_8));
        for (var segment : playlist.segments()) {
            assertThat(segment.uri()).matches("segments/[A-Za-z0-9._-]+\\.ts");
            Files.write(hls.resolve(segment.uri()), readHls(job.prefix() + segment.uri()));
        }
        assertThat(HlsOutputCheck.d27Violations(hls, MediaFixtures.sourceSeconds(H264_AAC_MP4))).isEmpty();
    }

    private byte[] readHls(String key) throws Exception {
        try (var response = backendStorage.getObject(GetObjectArgs.builder().bucket(HLS).object(key).build())) {
            return response.readAllBytes();
        }
    }

    private void assertMissingHls(String key) throws Exception {
        await("failed attempt prefix absent in Silo", () -> {
            try {
                readHls(key);
                return false;
            } catch (ErrorResponseException error) {
                if ("NoSuchKey".equals(error.errorResponse().code())) return true;
                throw error;
            }
        });
    }

    private void seedHls(String key) throws Exception {
        byte[] body = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        workerStorage.putObject(PutObjectArgs.builder().bucket(HLS).object(key)
                .stream(new ByteArrayInputStream(body), (long) body.length, -1L).build());
    }

    private void assertRawRetained(Job job) throws Exception {
        assertThat(workerStorage.statObject(StatObjectArgs.builder().bucket(RAW).object(job.rawKey()).build()).size())
                .isPositive();
    }

    private Map<String, String> settings(String probe, long reserveMb) {
        var settings = new LinkedHashMap<String, String>(Map.ofEntries(
                Map.entry("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + DATABASE),
                Map.entry("SPRING_DATASOURCE_USERNAME", required("POSTGRES_WORKER_USER")),
                Map.entry("SPRING_DATASOURCE_PASSWORD", required("POSTGRES_WORKER_PASSWORD")),
                Map.entry("WORKER_JDBC_CONNECT_TIMEOUT_SECONDS", "1"),
                Map.entry("WORKER_JDBC_READ_TIMEOUT_SECONDS", "2"),
                Map.entry("WORKER_JDBC_STATEMENT_TIMEOUT_MS", "1000"),
                Map.entry("RABBITMQ_HOST", "rabbitmq"),
                Map.entry("RABBITMQ_PORT", "5672"),
                Map.entry("RABBITMQ_USERNAME", required("RABBITMQ_WORKER_USERNAME")),
                Map.entry("RABBITMQ_PASSWORD", required("RABBITMQ_WORKER_PASSWORD")),
                Map.entry("RABBITMQ_ENCODING_QUEUE", QUEUE),
                Map.entry("MINIO_ENDPOINT", MINIO),
                Map.entry("MINIO_ACCESS_KEY", required("MINIO_WORKER_ACCESS_KEY")),
                Map.entry("MINIO_SECRET_KEY", required("MINIO_WORKER_SECRET_KEY")),
                Map.entry("MINIO_BUCKET_RAW", RAW), Map.entry("MINIO_BUCKET_HLS", HLS),
                Map.entry("MINIO_CONNECT_TIMEOUT_MS", "1000"), Map.entry("MINIO_READ_TIMEOUT_MS", "5000"),
                Map.entry("MINIO_WRITE_TIMEOUT_MS", "5000"),
                Map.entry("WORKER_CONCURRENCY", "1"), Map.entry("WORKER_PREFETCH", "1"),
                Map.entry("WORKER_TEMP_DIR", scratch.toString()),
                Map.entry("FFMPEG_PATH", ffmpeg.toString()), Map.entry("FFPROBE_PATH", probe),
                Map.entry("WORKER_FFPROBE_TIMEOUT_SECONDS", "15"),
                Map.entry("WORKER_FFMPEG_TIMEOUT_MIN_SECONDS", "30"),
                Map.entry("WORKER_FFMPEG_TIMEOUT_FACTOR", "10"),
                Map.entry("WORKER_MAX_DURATION_SECONDS", "10800"), Map.entry("WORKER_MAX_WIDTH", "1920"),
                Map.entry("WORKER_MAX_HEIGHT", "1080"), Map.entry("WORKER_MAX_STREAMS", "8"),
                Map.entry("HLS_SEGMENT_SECONDS", "6"), Map.entry("HLS_TARGET_HEIGHT", "720"),
                Map.entry("HLS_VIDEO_MAXRATE_KBPS", "4000"), Map.entry("WORKER_FFMPEG_THREADS", "1"),
                Map.entry("WORKER_OUTPUT_BUDGET_KBPS", "5000"),
                Map.entry("WORKER_DISK_RESERVE_MB", Long.toString(reserveMb))));
        settings.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        settings.put("SPRING_MAIN_BANNER_MODE", "off");
        return settings;
    }

    private static ConfigurableApplicationContext startWorker(Map<String, String> settings,
            OrchestrationPostgresFaults faults) {
        return new SpringApplicationBuilder(VodWorkerApplication.class).web(WebApplicationType.NONE)
                .registerShutdownHook(false).initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("orchestration-fixture", new LinkedHashMap<>(settings)));
                    if (faults != null) context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                        @Override
                        public Object postProcessBeforeInitialization(Object bean, String beanName) {
                            if (bean instanceof PGSimpleDataSource source) faults.route(source);
                            return bean;
                        }
                    });
                }).run();
    }

    private Process startChild(Map<String, String> settings) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = new ProcessBuilder(java, "-cp", classpath, VodWorkerApplication.class.getName());
        command.environment().putAll(settings);
        return command.redirectErrorStream(true).redirectOutput(sandbox.resolve("worker-child.log").toFile()).start();
    }

    private static void killChild(Process child) throws InterruptedException {
        var descendants = child.descendants().toList();
        child.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
        assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    private Path script(String name, String content) throws IOException {
        Path executable = Files.writeString(sandbox.resolve(name), "#!/bin/sh\n" + content);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static long reserve() {
        return Long.parseLong(System.getenv().getOrDefault("WORKER_TEST_DISK_RESERVE_MB", "1"));
    }

    private int calls() throws IOException {
        return Files.exists(encodeCalls) ? Files.readAllLines(encodeCalls).size() : 0;
    }

    private void assertNoJobDirectories() throws Exception {
        await("job directory removed", () -> {
            try (var paths = Files.list(scratch)) {
                return paths.noneMatch(path -> path.getFileName().toString().startsWith("job-"));
            }
        });
    }

    private static Connection connectRabbit(String role) throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost("rabbitmq");
        factory.setPort(5672);
        factory.setUsername(required(role + "_USERNAME"));
        factory.setPassword(required(role + "_PASSWORD"));
        factory.setConnectionTimeout(10_000);
        factory.setHandshakeTimeout(20_000);
        factory.setChannelRpcTimeout(10_000);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }

    private static MinioClient storage(String role) {
        return MinioClient.builder().endpoint(MINIO).region("us-east-1")
                .credentials(required(role + "_ACCESS_KEY"), required(role + "_SECRET_KEY")).build();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing fixture setting " + name);
        return value;
    }

    private static void await(String description, Callable<Boolean> condition) throws Exception {
        await(description, Duration.ofSeconds(30), condition);
    }

    private static void await(String description, Duration wait, Callable<Boolean> condition) throws Exception {
        long expires = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < expires) {
            if (condition.call()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private record Job(UUID job, UUID asset, int attempt, String rawKey) {
        String prefix() { return "video-assets/" + asset + "/attempt-" + attempt + "/"; }
    }

    private record Snapshot(String asset, String job, String master, String failure, String error, Timestamp started,
                            int duration, int width, int height, String bucket, Timestamp finished) {
        boolean ready() { return "READY".equals(asset) && "READY".equals(job); }
        boolean processing() { return "PROCESSING".equals(asset) && "PROCESSING".equals(job); }
        boolean failed(String code) {
            return "FAILED".equals(asset) && "FAILED".equals(job) && code.equals(failure) && code.equals(error)
                    && finished != null;
        }
    }
}
