package com.vodplatform.playback.web;

import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.playback.dto.PlaybackInfo;
import com.vodplatform.playback.service.PlaybackService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated playback-info endpoint; ADR-011 D11/D15 resume and error rules live in the service. */
@RestController
public class PlaybackController {

    private final PlaybackService playback;

    public PlaybackController(PlaybackService playback) {
        this.playback = playback;
    }

    @GetMapping("/api/v1/movies/{movieId}/playback")
    public PlaybackInfo getPlayback(@PathVariable UUID movieId,
                                    @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return playback.getPlayback(movieId, principal.userId());
    }
}
