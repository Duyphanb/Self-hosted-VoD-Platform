package com.vodplatform.worker.probe;

/**
 * The worker failure codes fixed by ADR-010 D28; a FAILED job stores exactly one of them. The probe uses the
 * first four, Issue 5.5 the encode codes and Issue 5.7 the rest. #107 owns the safe messages, redaction and
 * length cap; a database outage never produces FAILED (D20), so it has no code here.
 */
public enum WorkerFailureCode {
    /** FFprobe exited non-zero or produced output that is not valid, complete media metadata. */
    PROBE_FAILED,
    /** FFprobe passed {@code WORKER_FFPROBE_TIMEOUT_SECONDS}; its process tree was killed. */
    PROBE_TIMEOUT,
    /** Readable media the worker does not accept: an unknown key extension, a decoder outside the D26
     * allowlist, no selectable video stream or a rotation other than 0/90/180/270 degrees. */
    UNSUPPORTED_MEDIA,
    /** A D26 product cap (duration, long or short side, stream count) is exceeded. */
    MEDIA_LIMIT_EXCEEDED,
    /** FFmpeg exited non-zero or its HLS output failed the D27 validation. */
    ENCODE_FAILED,
    /** FFmpeg passed its duration-scaled deadline; its process tree was killed. */
    ENCODE_TIMEOUT,
    /** A D27 free-space check or the watchdog found less than the reserve. */
    INSUFFICIENT_DISK,
    /** The raw object is missing or unreadable, or the HLS upload failed after bounded retries. */
    STORAGE_ERROR,
    /** The worker stopped while the job was PROCESSING; startup recovery fails such orphaned pairs (D21). */
    WORKER_INTERRUPTED,
    /** Any other unexpected error. */
    INTERNAL_ERROR
}
