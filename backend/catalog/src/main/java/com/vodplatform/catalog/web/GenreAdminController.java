package com.vodplatform.catalog.web;

import com.vodplatform.catalog.dto.GenreResponse;
import com.vodplatform.catalog.dto.UpsertGenreRequest;
import com.vodplatform.catalog.service.GenreService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin genre endpoints ({@code /api/v1/admin/genres}; no delete). The URL rule for
 * {@code /api/v1/admin/**} authorizes {@code ROLE_ADMIN} first; {@code @PreAuthorize} is the
 * second layer. {@link GenreService} validates the body.
 */
@RestController
@RequestMapping("/api/v1/admin/genres")
@PreAuthorize("hasRole('ADMIN')")
public class GenreAdminController {

    private final GenreService genreService;

    public GenreAdminController(GenreService genreService) {
        this.genreService = genreService;
    }

    @GetMapping
    public List<GenreResponse> list() {
        return genreService.list();
    }

    @PostMapping
    public ResponseEntity<GenreResponse> create(@RequestBody UpsertGenreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(genreService.create(request));
    }

    @PutMapping("/{genreId}")
    public GenreResponse rename(@PathVariable UUID genreId, @RequestBody UpsertGenreRequest request) {
        return genreService.rename(genreId, request);
    }
}
