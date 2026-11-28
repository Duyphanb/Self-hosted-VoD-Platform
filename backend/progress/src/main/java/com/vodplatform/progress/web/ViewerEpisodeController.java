package com.vodplatform.progress.web;

import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.progress.service.ViewerEpisodes;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ViewerEpisodeController {
    private final ViewerEpisodes episodes;

    public ViewerEpisodeController(ViewerEpisodes episodes) {
        this.episodes = episodes;
    }

    @GetMapping("/api/v1/titles/{titleId}/episodes")
    public ViewerEpisodes.EpisodePage list(@PathVariable UUID titleId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(CatalogPaging.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(CatalogPaging.MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return episodes.list(titleId, principal.userId(), page, size);
    }

    @GetMapping("/api/v1/episodes/{episodeId}")
    public ViewerEpisodes.EpisodeDetail get(@PathVariable UUID episodeId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return episodes.get(episodeId, principal.userId());
    }
}
