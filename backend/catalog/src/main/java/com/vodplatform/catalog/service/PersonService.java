package com.vodplatform.catalog.service;

import com.vodplatform.catalog.dto.PersonResponse;
import com.vodplatform.catalog.dto.UpsertPersonRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.exception.PersonNotFoundException;
import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin people management (Issue 3.3): search, create and rename; people are never deleted. Names
 * are normalized and not unique; the server generates the unique slug (ADR-009, see
 * {@link PersonWriter}). Returns DTOs only.
 *
 * <p>Create and rename run through {@link CatalogWriteRetry}, each attempt in a new transaction: a
 * rename locks the person row and may then wait for a slug that a movie write holds, while that
 * movie write waits for the same person row to reference it in a credit, and PostgreSQL aborts one
 * of the two as a deadlock victim.
 *
 * <p>Each rename attempt loads the person with a row lock ({@link PersonRepository#findForUpdateById})
 * before it decides on the slug. A rename that read the person without the lock could decide on a
 * name and slug that a concurrent rename had already replaced: it kept a slug the person no longer
 * had, which another person could have taken by then, or excluded that slug from the collision
 * check as the person's own.
 */
@Service
public class PersonService {

    private final PersonRepository personRepository;
    private final PersonWriter personWriter;
    private final CatalogWriteRetry writeRetry;

    PersonService(PersonRepository personRepository, CatalogWriteRetry writeRetry) {
        this.personRepository = personRepository;
        this.personWriter = new PersonWriter(personRepository);
        this.writeRetry = writeRetry;
    }

    /**
     * Searches people for credit assignment, ordered by name, then id. The term is normalized with
     * the slug rule and matched as a substring of the person slug, so matching ignores case and
     * diacritics; an absent term, or one that is empty after normalization, lists everyone.
     *
     * @param term the raw search term, or null
     * @param page zero-based page index, at most {@value CatalogPaging#MAX_PAGE}
     * @param size page size, from 1 to {@value CatalogPaging#MAX_PAGE_SIZE}
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR} for a term longer than the
     *                                    contract allows
     * @throws IllegalArgumentException   if the page or size is outside the contract range; the API
     *                                    layer validates them first
     */
    @Transactional(readOnly = true)
    public Page<PersonResponse> search(String term, int page, int size) {
        String slugFragment = NameRequestValidator.searchSlug(term);
        Pageable pageable = CatalogPaging.pageRequest(page, size, Sort.unsorted());
        Page<Person> people = slugFragment == null
                ? personRepository.findAllByOrderByNameAscIdAsc(pageable)
                : personRepository.findBySlugContainingOrderByNameAscIdAsc(slugFragment, pageable);
        return people.map(PersonService::toResponse);
    }

    /**
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR}
     */
    public PersonResponse create(UpsertPersonRequest request) {
        String name = NameRequestValidator.personName(request);
        return writeRetry.execute(() -> toResponse(personWriter.create(name)));
    }

    /**
     * Renames a person and regenerates the slug from the new name; the slug is kept when its base
     * is unchanged. A concurrent rename of the same person is waited for, and this one then starts
     * from the name and slug it committed.
     *
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR}
     * @throws PersonNotFoundException    404 {@code PERSON_NOT_FOUND}
     */
    public PersonResponse rename(UUID personId, UpsertPersonRequest request) {
        Objects.requireNonNull(personId, "personId");
        String name = NameRequestValidator.personName(request);
        return writeRetry.execute(() -> {
            Person person = personRepository.findForUpdateById(personId)
                    .orElseThrow(PersonNotFoundException::new);
            personWriter.rename(person, name);
            return toResponse(person);
        });
    }

    private static PersonResponse toResponse(Person person) {
        return new PersonResponse(person.getId(), person.getName(), person.getSlug());
    }
}
