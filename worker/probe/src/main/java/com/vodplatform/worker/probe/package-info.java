/**
 * Worker probe module (Issue 5.4): the FFprobe service, its settings and metadata, and the media-handling
 * pieces the later worker issues share: the ADR-010 D28 failure codes, the D6 trusted demuxer table, the D26
 * decoder allowlist and, in {@code process}, the bounded process runner. The transcode module (Issue 5.5) may
 * depend on this module; this module depends on no other worker module.
 */
package com.vodplatform.worker.probe;
