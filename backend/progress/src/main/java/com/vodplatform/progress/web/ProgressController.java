package com.vodplatform.progress.web;

import com.vodplatform.auth.security.AuthenticatedUserPrincipal;
import com.vodplatform.progress.dto.ProgressRequest;
import com.vodplatform.progress.dto.ProgressResponse;
import com.vodplatform.progress.service.ProgressService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated per-user progress; the caller cannot supply the owner id. */
@RestController
@RequestMapping("/api/v1/movies/{movieId}/progress")
public class ProgressController {
    private final ProgressService service;
    public ProgressController(ProgressService service) {
        this.service = service;
    }
    @GetMapping
    public ProgressResponse get(@PathVariable UUID movieId, @AuthenticationPrincipal AuthenticatedUserPrincipal user) {
        return service.get(user.userId(), movieId);
    }
    @PutMapping
    public ProgressResponse put(@PathVariable UUID movieId, @Valid @RequestBody ProgressRequest request,
                               @AuthenticationPrincipal AuthenticatedUserPrincipal user) {
        return service.save(user.userId(), movieId, request);
    }
}
