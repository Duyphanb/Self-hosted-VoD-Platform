package com.vodplatform.catalog.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ADR-009 slug rule, used for genre and person slugs and for genre and people search input:
 * decompose Unicode (NFD), remove combining marks, map đ/Đ to d, lower-case, replace every run of
 * characters other than a-z and 0-9 with a hyphen, and trim leading and trailing hyphens.
 *
 * <p>Only canonical decomposition is applied, so letters without a decomposition into a-z (for
 * example ß, Ø or CJK characters) act as separators. Each input character yields at most one
 * output character, so a slug is never longer than its input: a genre name of at most 100
 * characters fits the 120-character {@code genres.slug} column.
 *
 * <p>Movie slugs are admin input. The backend validates them with {@link #isMovieSlug(String)} and
 * never generates or rewrites them.
 */
public final class SlugNormalizer {

    /** Maximum length of a movie slug ({@code movies.slug}, API contract). */
    public static final int MOVIE_SLUG_MAX_LENGTH = 255;

    /** Maximum length of a generated person slug base, before any collision suffix. */
    public static final int PERSON_SLUG_BASE_MAX_LENGTH = 240;

    /** Person slug base used when a name yields an empty slug. */
    public static final String PERSON_SLUG_FALLBACK_BASE = "person";

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC_RUN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private SlugNormalizer() {
    }

    /**
     * Applies the slug rule. The result is empty when the value has no a-z or 0-9 character
     * after normalization; callers decide whether that is an error (genre names, the
     * {@code genre} filter), absent input (the people search term) or the person fallback.
     *
     * @param value the text to normalize; must not be null
     */
    public static String slugify(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String mapped = withoutMarks.replace('đ', 'd').replace('Đ', 'd');
        String lowerCase = mapped.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC_RUN.matcher(lowerCase).replaceAll("-");
        return trimHyphens(hyphenated);
    }

    /**
     * Returns whether the value matches the slug pattern {@code ^[a-z0-9]+(-[a-z0-9]+)*$}, with no
     * length limit.
     *
     * @param value the text to check; must not be null
     */
    public static boolean isSlug(String value) {
        return SLUG.matcher(value).matches();
    }

    /**
     * Returns whether the value is an acceptable movie slug: the slug pattern and at most
     * {@value #MOVIE_SLUG_MAX_LENGTH} characters. The value is checked as given, never rewritten.
     *
     * @param value the admin input to check; must not be null
     */
    public static boolean isMovieSlug(String value) {
        return value.length() <= MOVIE_SLUG_MAX_LENGTH && isSlug(value);
    }

    /**
     * Returns the base of a generated person slug: the slug of the name, truncated to
     * {@value #PERSON_SLUG_BASE_MAX_LENGTH} characters without a trailing hyphen, or
     * {@value #PERSON_SLUG_FALLBACK_BASE} when the name yields an empty slug. The caller adds a
     * {@code -2}, {@code -3}, ... suffix on collision.
     *
     * @param name the normalized person name; must not be null
     */
    public static String personSlugBase(String name) {
        String slug = slugify(name);
        if (slug.length() > PERSON_SLUG_BASE_MAX_LENGTH) {
            slug = trimHyphens(slug.substring(0, PERSON_SLUG_BASE_MAX_LENGTH));
        }
        return slug.isEmpty() ? PERSON_SLUG_FALLBACK_BASE : slug;
    }

    private static String trimHyphens(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }
}
