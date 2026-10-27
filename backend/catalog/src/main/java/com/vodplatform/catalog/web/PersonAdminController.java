package com.vodplatform.catalog.web;

import com.vodplatform.catalog.dto.PersonPageResponse;
import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.service.CatalogPaging;
import com.vodplatform.catalog.service.PersonService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin people endpoints ({@code /api/v1/admin/people}; no delete). The URL rule for
 * {@code /api/v1/admin/**} authorizes {@code ROLE_ADMIN} first; {@code @PreAuthorize} is the
 * second layer. Page parameters are validated here; {@link PersonService} validates the search
 * term and the body.
 */
@RestController
@RequestMapping("/api/v1/admin/people")
@PreAuthorize("hasRole('ADMIN')")
public class PersonAdminController {

    private final PersonService personService;

    public PersonAdminController(PersonService personService) {
        this.personService = personService;
    }

    @GetMapping
    public PersonPageResponse search(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = CatalogParameters.PAGE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE, message = CatalogParameters.PAGE_RANGE) int page,
            @RequestParam(name = "size", defaultValue = "" + CatalogPaging.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = CatalogParameters.SIZE_RANGE)
            @Max(value = CatalogPaging.MAX_PAGE_SIZE, message = CatalogParameters.SIZE_RANGE) int size
    ) {
        return PersonPageResponse.of(personService.search(q, page, size));
    }

    @PostMapping
    public ResponseEntity<PersonResponse> create(@RequestBody UpsertPersonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(personService.create(request));
    }

    @PutMapping("/{personId}")
    public PersonResponse rename(@PathVariable UUID personId, @RequestBody UpsertPersonRequest request) {
        return personService.rename(personId, request);
    }
}
