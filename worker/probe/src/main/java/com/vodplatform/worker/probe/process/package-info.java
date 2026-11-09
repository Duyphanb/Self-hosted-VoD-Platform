/**
 * The bounded media process runner (ADR-010 D6-D10): typed argument lists without a shell, closed stdin, a
 * cleared environment, concurrently drained bounded output and a process-tree deadline. FFprobe (Issue
 * 5.4) uses it here; the transcode module reuses it for FFmpeg (Issue 5.5).
 */
package com.vodplatform.worker.probe.process;
