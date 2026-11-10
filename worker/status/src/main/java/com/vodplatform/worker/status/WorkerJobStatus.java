package com.vodplatform.worker.status;

import com.vodplatform.worker.probe.MediaMetadata;
import com.vodplatform.worker.probe.WorkerFailureCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Short, paired JDBC transactions for D19/D20. The caller owns delivery retries and media work. */
public final class WorkerJobStatus {
    private final DataSource dataSource;

    public WorkerJobStatus(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public record Attempt(UUID jobId, UUID assetId, int number) {
        public Attempt {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(assetId, "assetId");
            if (number < 1) throw new IllegalArgumentException("Attempt must be positive");
        }
    }

    public record RawObject(String bucket, String objectKey) {}

    public enum ClaimOutcome { ACQUIRED, RETRY, STALE, INVALID, UNRESOLVED }

    /** Raw keys are available only to a delivery which owns the claim. */
    public record Claim(ClaimOutcome outcome, RawObject raw) {}

    public record HlsObject(String bucket, String masterKey) {
        public HlsObject {
            if (bucket == null || bucket.isBlank() || bucket.length() > 100
                    || masterKey == null || masterKey.isBlank() || masterKey.length() > 512) {
                throw new IllegalArgumentException("Invalid HLS object location");
            }
        }
    }

    public sealed interface Completion permits Ready, Failed {}

    public record Ready(HlsObject hls, MediaMetadata metadata) implements Completion {
        public Ready {
            Objects.requireNonNull(hls, "hls");
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    public record Failed(WorkerFailureCode code) implements Completion {
        public Failed {
            Objects.requireNonNull(code, "code");
        }
    }

    public enum FinishOutcome { COMPLETED, RETRY, LOST, UNRESOLVED }

    /** The current asset reference must be checked before deleting any lost finish's attempt prefix. */
    public record Finish(FinishOutcome outcome, HlsObject referencedHls) {}

    public static final class CommitOutcomeUnknownException extends SQLException {
        private CommitOutcomeUnknownException() {
            super("Worker status commit outcome requires readback");
        }
    }

    public Claim claim(Attempt attempt) throws SQLException {
        return transaction(connection -> {
            Pair pair = lockPair(connection, attempt);
            Claim observed = classifyClaim(pair, false);
            if (observed.outcome() != ClaimOutcome.RETRY) return observed;

            requireOne(update(connection, """
                    UPDATE video_assets SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'QUEUED'
                    """, attempt.assetId()));
            requireOne(update(connection, """
                    UPDATE encoding_jobs SET status = 'PROCESSING', started_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND video_asset_id = ? AND attempt = ? AND status = 'QUEUED'
                    """, attempt.jobId(), attempt.assetId(), attempt.number()));
            return new Claim(ClaimOutcome.ACQUIRED, pair.asset().raw());
        });
    }

    /** Call only when this delivery's claim COMMIT outcome is unknown. */
    public Claim readClaimAfterUnknownCommit(Attempt attempt) throws SQLException {
        return transaction(connection -> classifyClaim(lockPair(connection, attempt), true));
    }

    public Finish finish(Attempt attempt, Completion completion) throws SQLException {
        Objects.requireNonNull(completion, "completion");
        return transaction(connection -> {
            Pair pair = lockPair(connection, attempt);
            Finish observed = classifyFinish(pair, completion);
            if (observed.outcome() != FinishOutcome.RETRY) return observed;

            String status;
            String error = null;
            HlsObject hls = pair.asset().hls();
            switch (completion) {
                case Ready ready -> {
                    status = "READY";
                    hls = ready.hls();
                    MediaMetadata media = ready.metadata();
                    requireOne(update(connection, """
                            UPDATE video_assets
                            SET status = 'READY', hls_bucket = ?, hls_master_object_key = ?,
                                duration_seconds = ?, width = ?, height = ?, codec = ?, bitrate = ?,
                                failure_reason = NULL, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND status = 'PROCESSING'
                            """, hls.bucket(), hls.masterKey(), media.durationSeconds(), media.width(),
                            media.height(), media.codec(), media.bitrate(), attempt.assetId()));
                }
                case Failed failed -> {
                    status = "FAILED";
                    error = failed.code().name();
                    requireOne(update(connection, """
                            UPDATE video_assets SET status = 'FAILED', failure_reason = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ? AND status = 'PROCESSING'
                            """, error, attempt.assetId()));
                }
            }
            requireOne(update(connection, """
                    UPDATE encoding_jobs SET status = ?, error_message = ?, finished_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND video_asset_id = ? AND attempt = ? AND status = 'PROCESSING'
                    """, status, error, attempt.jobId(), attempt.assetId(), attempt.number()));
            return new Finish(FinishOutcome.COMPLETED, hls);
        });
    }

    public Finish readFinishAfterUnknownCommit(Attempt attempt, Completion completion) throws SQLException {
        Objects.requireNonNull(completion, "completion");
        return transaction(connection -> classifyFinish(lockPair(connection, attempt), completion));
    }

    private static Claim classifyClaim(Pair pair, boolean recoveringOwnClaim) {
        if (pair.job() == null) {
            return new Claim(recoveringOwnClaim ? ClaimOutcome.UNRESOLVED : ClaimOutcome.INVALID, null);
        }
        if (pair.job().superseded() || pair.terminal()) return new Claim(ClaimOutcome.STALE, null);
        if (pair.both("QUEUED")) return new Claim(ClaimOutcome.RETRY, null);
        if (recoveringOwnClaim && pair.both("PROCESSING")) {
            return new Claim(ClaimOutcome.ACQUIRED, pair.asset().raw());
        }
        return new Claim(ClaimOutcome.UNRESOLVED, null);
    }

    private static Finish classifyFinish(Pair pair, Completion completion) {
        HlsObject referenced = pair.asset() == null ? null : pair.asset().hls();
        if (pair.job() == null) return new Finish(FinishOutcome.UNRESOLVED, referenced);
        if (pair.both("READY") && referenced == null) return new Finish(FinishOutcome.UNRESOLVED, null);
        if (pair.job().superseded()) return new Finish(FinishOutcome.LOST, referenced);
        if (pair.both("PROCESSING")) return new Finish(FinishOutcome.RETRY, referenced);
        boolean completed = switch (completion) {
            case Ready ready -> pair.both("READY") && ready.hls().equals(referenced);
            case Failed failed -> pair.both("FAILED") && failed.code().name().equals(pair.asset().failure())
                    && failed.code().name().equals(pair.job().error());
        };
        if (completed) return new Finish(FinishOutcome.COMPLETED, referenced);
        return new Finish(pair.terminal() ? FinishOutcome.LOST : FinishOutcome.UNRESOLVED, referenced);
    }

    private static Pair lockPair(Connection connection, Attempt attempt) throws SQLException {
        // Lock the parent first. This also blocks new job FK inserts while we verify the latest attempt.
        Asset asset;
        try (var statement = prepare(connection, """
                SELECT status, raw_bucket, raw_object_key, hls_bucket, hls_master_object_key, failure_reason
                FROM video_assets WHERE id = ? FOR UPDATE
                """, attempt.assetId()); var rows = statement.executeQuery()) {
            if (!rows.next()) return new Pair(null, null);
            String bucket = rows.getString("hls_bucket");
            String master = rows.getString("hls_master_object_key");
            HlsObject hls = bucket == null || bucket.isBlank() || master == null || master.isBlank()
                    ? null : new HlsObject(bucket, master);
            asset = new Asset(rows.getString("status"),
                    new RawObject(rows.getString("raw_bucket"), rows.getString("raw_object_key")),
                    hls, rows.getString("failure_reason"));
        }
        try (var statement = prepare(connection, """
                SELECT job.status, job.error_message, EXISTS (
                    SELECT FROM encoding_jobs newer WHERE newer.video_asset_id = job.video_asset_id
                    AND newer.attempt > job.attempt
                ) AS superseded
                FROM encoding_jobs job WHERE job.id = ? AND job.video_asset_id = ? AND job.attempt = ?
                FOR UPDATE OF job
                """, attempt.jobId(), attempt.assetId(), attempt.number()); var rows = statement.executeQuery()) {
            Job job = rows.next()
                    ? new Job(rows.getString("status"), rows.getString("error_message"), rows.getBoolean("superseded"))
                    : null;
            return new Pair(asset, job);
        }
    }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        boolean commitAttempted = false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            T result;
            try {
                result = work.execute(connection);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Closing the connection also ends an open PostgreSQL transaction.
                }
                throw failure;
            }
            commitAttempted = true;
            connection.commit();
            return result;
        } catch (SQLException failure) {
            if (commitAttempted) throw new CommitOutcomeUnknownException();
            throw new SQLException("Worker status transaction failed", failure.getSQLState());
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... arguments)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement;
        } catch (SQLException failure) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                // Keep the bind failure.
            }
            throw failure;
        }
    }

    private static int update(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = prepare(connection, sql, arguments)) {
            return statement.executeUpdate();
        }
    }

    private static void requireOne(int changed) throws SQLException {
        if (changed != 1) throw new SQLException("Worker status update did not affect one row");
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record Asset(String status, RawObject raw, HlsObject hls, String failure) {}
    private record Job(String status, String error, boolean superseded) {}

    private record Pair(Asset asset, Job job) {
        boolean both(String status) {
            return job != null && status.equals(asset.status()) && status.equals(job.status());
        }

        boolean terminal() {
            return (both("READY") && asset.hls() != null) || both("FAILED");
        }
    }
}
