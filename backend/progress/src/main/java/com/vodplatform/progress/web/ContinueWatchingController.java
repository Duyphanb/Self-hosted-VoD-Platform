package com.vodplatform.progress.web;

import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.progress.dto.ContinueWatchingItem;
import com.vodplatform.progress.service.ContinueWatchingService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated array endpoint; the owner comes only from the verified bearer principal. */
@RestController
public class ContinueWatchingController {
    private final ContinueWatchingService service;

    public ContinueWatchingController(ContinueWatchingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/me/continue-watching")
    public List<ContinueWatchingItem> get(@AuthenticationPrincipal AuthenticatedUserPrincipal user) {
        return service.get(user.userId());
    }
}
