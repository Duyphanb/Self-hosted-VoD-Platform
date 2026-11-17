package com.vodplatform.playback.web;

import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.playback.dto.PlaybackInfo;
import com.vodplatform.playback.service.PlaybackService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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
