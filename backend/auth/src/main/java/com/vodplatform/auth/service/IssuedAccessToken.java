package com.vodplatform.auth.service;

public record IssuedAccessToken(String value, long expiresInSeconds) {
}
