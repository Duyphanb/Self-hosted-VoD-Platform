package com.vodplatform.playback.service;

/** Authorized internal storage coordinates, never a public response DTO. */
public record HlsAccess(String bucket, String masterKey, String attemptPrefix) {
}
