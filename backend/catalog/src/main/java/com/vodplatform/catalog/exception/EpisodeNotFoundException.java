package com.vodplatform.catalog.exception;

import com.vodplatform.shared.error.ApiException;
import org.springframework.http.HttpStatus;

public class EpisodeNotFoundException extends ApiException {
    public EpisodeNotFoundException() {
        super(HttpStatus.NOT_FOUND, "EPISODE_NOT_FOUND", "Episode not found");
    }
}
