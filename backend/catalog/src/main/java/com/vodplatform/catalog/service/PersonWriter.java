package com.vodplatform.catalog.service;

import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import com.vodplatform.catalog.text.SlugNormalizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and renames people with the server-generated slug of ADR-009: the person slug base of
 * the name ({@link SlugNormalizer#personSlugBase(String)}), or the first free {@code -2},
 * {@code -3}, ... suffix when another person has it. Used by the admin people endpoints and by
 * name-only credits, inside the caller's transaction.
 *
 * <p>A slug chosen from the slugs visible now can be written by a concurrent transaction before
 * this one writes it. The repository writes report that as a result rather than a failed
 * statement ({@link PersonRepository#insertIfSlugFree}, {@link PersonRepository#renameIfSlugFree}),
 * so the next attempt reads the slugs again, now including the one just taken, and picks the next
 * suffix. Only a burst of more than {@value #MAX_ATTEMPTS} concurrent writes of the same base
 * exhausts the attempts.
 */
final class PersonWriter {

    /**
     * Slug writes per create, or per rename that regenerates the slug. Each failed write means that
     * another transaction committed the chosen slug after this one read the slugs, so the next
     * write reads them again and picks another.
     */
    static final int MAX_ATTEMPTS = 10;

    private final PersonRepository personRepository;

    PersonWriter(PersonRepository personRepository) {
        this.personRepository = Objects.requireNonNull(personRepository, "personRepository");
    }

    /**
     * Inserts a person and returns it, managed by the current persistence context.
     *
     * @param name the normalized name
     * @throws IllegalStateException if every attempt found its slug taken
     */
    Person create(String name) {
        Objects.requireNonNull(name, "name");
        String base = SlugNormalizer.personSlugBase(name);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String slug = firstFreeSlug(base, personRepository.findSlugsWithBase(base), null);
            UUID id = UUID.randomUUID();
            if (personRepository.insertIfSlugFree(id, name, slug) == 1) {
                return personRepository.findById(id).orElseThrow();
            }
        }
        throw new IllegalStateException("No free person slug after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Renames a person. The current slug is kept when the slug base of the new name equals that of
     * the current name; otherwise the slug is regenerated, and the person's own slug does not count
     * as a collision.
     *
     * <p>Both decisions use the person's name and slug, so the person must be locked. Otherwise a
     * concurrent rename could replace them after they were read: the slug kept for an unchanged
     * base could then belong to another person, and the slug excluded from the collision check
     * could be another person's while the person's own slug counted as taken.
     *
     * @param person a person loaded with a row lock in the current transaction
     *               ({@link PersonRepository#findForUpdateById}); refreshed with the stored values
     * @param name   the normalized new name
     * @throws IllegalStateException if every attempt found its slug taken, or if the person was
     *                               not locked and another person has the slug it is to keep
     */
    void rename(Person person, String name) {
        Objects.requireNonNull(person, "person");
        Objects.requireNonNull(name, "name");
        String base = SlugNormalizer.personSlugBase(name);
        if (base.equals(SlugNormalizer.personSlugBase(person.getName()))) {
            // The locked person still has this slug, and no other person can write a slug that
            // this row holds, so keeping it cannot collide.
            if (!personRepository.renameIfSlugFree(person, name, person.getSlug())) {
                throw new IllegalStateException(
                        "Another person has the slug of the person being renamed; it was not loaded with a row lock");
            }
            return;
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String slug = firstFreeSlug(base, personRepository.findSlugsWithBase(base), person.getSlug());
            if (personRepository.renameIfSlugFree(person, name, slug)) {
                return;
            }
        }
        throw new IllegalStateException("No free person slug after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Returns the base when it is free, otherwise {@code base-N} for the smallest free N from 2.
     * Slugs that start with the base but carry another tail ({@code base-jr}, {@code base-02}) do
     * not collide with any candidate and are ignored.
     *
     * @param takenSlugs slugs that start with the base ({@link PersonRepository#findSlugsWithBase})
     * @param ownSlug    the slug of the person being renamed, which never counts as taken; null on
     *                   create
     */
    static String firstFreeSlug(String base, Collection<String> takenSlugs, String ownSlug) {
        boolean baseTaken = false;
        Set<Integer> takenSuffixes = new HashSet<>();
        String prefix = base + "-";
        for (String slug : takenSlugs) {
            if (slug.equals(ownSlug)) {
                continue;
            }
            if (slug.equals(base)) {
                baseTaken = true;
            } else if (slug.startsWith(prefix)) {
                Integer suffix = suffix(slug.substring(prefix.length()));
                if (suffix != null) {
                    takenSuffixes.add(suffix);
                }
            }
        }
        if (!baseTaken) {
            return base;
        }
        int suffix = 2;
        while (takenSuffixes.contains(suffix)) {
            suffix++;
        }
        return prefix + suffix;
    }

    /**
     * Parses a generated suffix: digits without a leading zero, small enough for an int. Returns
     * null for any other tail.
     */
    private static Integer suffix(String tail) {
        if (tail.isEmpty() || tail.length() > 9 || tail.charAt(0) == '0') {
            return null;
        }
        for (int index = 0; index < tail.length(); index++) {
            char character = tail.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return Integer.parseInt(tail);
    }
}
