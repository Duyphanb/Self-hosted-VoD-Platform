/**
 * Upload ceiling in MiB, matching Nginx `2048m` and Spring `2048MB`.
 * The request ceiling includes multipart overhead; a file at this byte ceiling
 * can still be rejected by the server once the multipart envelope is added.
 */
export const MAX_UPLOAD_SIZE_MB = 2048;

/** Convert MiB to bytes (1 MiB = 1024 * 1024 bytes), not decimal megabytes. */
export const MAX_UPLOAD_SIZE_BYTES = MAX_UPLOAD_SIZE_MB * 1024 * 1024;
