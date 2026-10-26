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
 */
@Service
public class PersonService {

    private final PersonRepository personRepository;
    private final PersonWriter personWriter;

    PersonService(PersonRepository personRepository) {
        this.personRepository = personRepository;
        this.personWriter = new PersonWriter(personRepository);
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
    @Transactional
    public PersonResponse create(UpsertPersonRequest request) {
        String name = NameRequestValidator.personName(request);
        return toResponse(personWriter.create(name));
    }

    /**
     * Renames a person and regenerates the slug from the new name; the slug is kept when its base
     * is unchanged.
     *
     * @throws CatalogValidationException 400 {@code VALIDATION_ERROR}
     * @throws PersonNotFoundException    404 {@code PERSON_NOT_FOUND}
     */
    @Transactional
    public PersonResponse rename(UUID personId, UpsertPersonRequest request) {
        Objects.requireNonNull(personId, "personId");
        String name = NameRequestValidator.personName(request);
        Person person = personRepository.findById(personId).orElseThrow(PersonNotFoundException::new);
        personWriter.rename(person, name);
        return toResponse(person);
    }

    private static PersonResponse toResponse(Person person) {
        return new PersonResponse(person.getId(), person.getName(), person.getSlug());
    }
}
