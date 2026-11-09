/**
 * Worker transcode module (Issue 5.5): the FFmpeg HLS service, its settings and command, the progress parser
 * and the ADR-010 D27 output validator. It reuses the probe module's process runner, D28 failure codes, trusted
 * demuxer table, D26 decoder allowlist and {@code MediaMetadata}; it depends on no other worker module.
 */
package com.vodplatform.worker.transcode;
