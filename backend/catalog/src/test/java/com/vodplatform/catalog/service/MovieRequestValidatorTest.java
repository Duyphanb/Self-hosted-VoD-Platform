package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.vodplatform.catalog.dto.MovieCreditInput;
import com.vodplatform.catalog.dto.UpsertMovieRequest;
import com.vodplatform.catalog.exception.CatalogValidationException;
import com.vodplatform.catalog.persistence.CreditRole;
import com.vodplatform.catalog.persistence.MaturityRating;
import com.vodplatform.catalog.persistence.MovieStatus;
import com.vodplatform.catalog.service.ValidatedMovie.CreditInput;
import com.vodplatform.catalog.service.ValidatedMovie.GenreInput;
import com.vodplatform.shared.error.ApiFieldError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class MovieRequestValidatorTest {

    private static final UUID PERSON_ID = new UUID(0L, 201);

    @Test
    void acceptsAValidRequestAndNormalizesItsText() {
        UpsertMovieRequest request = new UpsertMovieRequest(
                "  Đất Rừng  Phương Nam ",
                "dat-rung-phuong-nam",
                "\n  Dòng 1\r\n\tDòng 2  \n",
                2023,
                MaturityRating.T13,
                List.of("  Hành   Động ", "Hài"),
                List.of(
                        new MovieCreditInput(PERSON_ID, "ignored\u0000name", CreditRole.DIRECTOR),
                        new MovieCreditInput(null, "  Trấn   Thành ", CreditRole.ACTOR)
                ),
                MovieStatus.PUBLISHED
        );

        ValidatedMovie movie = MovieRequestValidator.validate(request);

        assertThat(movie.title()).isEqualTo("Đất Rừng  Phương Nam");
        assertThat(movie.slug()).isEqualTo("dat-rung-phuong-nam");
        assertThat(movie.description()).isEqualTo("Dòng 1\r\n\tDòng 2");
        assertThat(movie.releaseYear()).isEqualTo(2023);
        assertThat(movie.maturityRating()).isEqualTo(MaturityRating.T13);
        assertThat(movie.status()).isEqualTo(MovieStatus.PUBLISHED);
        assertThat(movie.genres()).containsExactly(new GenreInput("Hành Động", "hanh-dong"), new GenreInput("Hài", "hai"));
        // With a personId the name is ignored, so its control character is not an error.
        assertThat(movie.people()).containsExactly(
                new CreditInput(0, PERSON_ID, null, CreditRole.DIRECTOR),
                new CreditInput(1, null, "Trấn Thành", CreditRole.ACTOR)
        );
    }

    @Test
    void optionalValuesMayBeNullAndPeopleMayBeEmpty() {
        ValidatedMovie movie = MovieRequestValidator.validate(new MovieRequestBuilder()
                .releaseYear(null)
                .maturityRating(null)
                .people(List.of())
                .build());

        assertThat(movie.releaseYear()).isNull();
        assertThat(movie.maturityRating()).isNull();
        assertThat(movie.people()).isEmpty();
    }

    @Test
    void reportsEveryMissingRequiredValueInFieldOrder() {
        UpsertMovieRequest request = new UpsertMovieRequest(null, null, null, null, null, null, null, null);

        assertThat(fieldErrors(request)).containsExactly(
                tuple("description", "must not be null"),
                tuple("genres", "must not be null"),
                tuple("people", "must not be null"),
                tuple("slug", "must not be null"),
                tuple("status", "must not be null"),
                tuple("title", "must not be null")
        );
    }

    @Test
    void failuresUseTheValidationErrorResponse() {
        CatalogValidationException exception = catchThrowableOfType(
                CatalogValidationException.class,
                () -> MovieRequestValidator.validate(new MovieRequestBuilder().title(" ").build())
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(exception.getMessage()).isEqualTo("Request validation failed");
    }

    @Test
    void releaseYearMustBeWithinTheContractRange() {
        assertThat(MovieRequestValidator.validate(new MovieRequestBuilder().releaseYear(1888).build()).releaseYear()).isEqualTo(1888);
        assertThat(MovieRequestValidator.validate(new MovieRequestBuilder().releaseYear(2100).build()).releaseYear()).isEqualTo(2100);
        assertThat(fieldErrors(new MovieRequestBuilder().releaseYear(1887).build()))
                .containsExactly(tuple("releaseYear", "must be between 1888 and 2100"));
        assertThat(fieldErrors(new MovieRequestBuilder().releaseYear(2101).build()))
                .containsExactly(tuple("releaseYear", "must be between 1888 and 2100"));
    }

    @Test
    void textLengthsFollowTheContractLimits() {
        assertAccepted(new MovieRequestBuilder().title("t".repeat(255)));
        assertAccepted(new MovieRequestBuilder().description("d".repeat(5000)));
        assertAccepted(new MovieRequestBuilder().slug("s".repeat(255)));
        assertAccepted(new MovieRequestBuilder().genres(List.of("g".repeat(100))));
        assertAccepted(new MovieRequestBuilder().people(List.of(new MovieCreditInput(null, "n".repeat(255), CreditRole.ACTOR))));

        assertThat(fieldErrors(new MovieRequestBuilder().title("t".repeat(256)).build()))
                .containsExactly(tuple("title", "size must be between 1 and 255"));
        assertThat(fieldErrors(new MovieRequestBuilder().description("d".repeat(5001)).build()))
                .containsExactly(tuple("description", "size must be between 1 and 5000"));
        assertThat(fieldErrors(new MovieRequestBuilder().slug("s".repeat(256)).build()))
                .containsExactly(tuple("slug", "size must be between 1 and 255"));
        assertThat(fieldErrors(new MovieRequestBuilder().genres(List.of("g".repeat(101))).build()))
                .containsExactly(tuple("genres[0]", "size must be between 1 and 100"));
        assertThat(fieldErrors(new MovieRequestBuilder()
                .people(List.of(new MovieCreditInput(null, "n".repeat(256), CreditRole.ACTOR)))
                .build()))
                .containsExactly(tuple("people[0].name", "size must be between 1 and 255"));
    }

    @Test
    void lengthsCountCodePointsAsTheContractDoes() {
        String emoji = "🎬";

        assertAccepted(new MovieRequestBuilder().title(emoji.repeat(255)));
        assertThat(fieldErrors(new MovieRequestBuilder().title(emoji.repeat(256)).build()))
                .containsExactly(tuple("title", "size must be between 1 and 255"));
    }

    @Test
    void blankTextIsRejectedAfterTrimming() {
        UpsertMovieRequest request = new MovieRequestBuilder()
                .title("   ")
                .description("\r\n\t")
                .genres(List.of("Hài", "　"))
                .people(List.of(new MovieCreditInput(null, "  ", CreditRole.ACTOR)))
                .build();

        assertThat(fieldErrors(request)).containsExactly(
                tuple("description", "must not be blank"),
                tuple("genres[1]", "must not be blank"),
                tuple("people[0].name", "must not be blank"),
                tuple("title", "must not be blank")
        );
    }

    @Test
    void controlCharactersAreRejectedExceptLineBreaksAndTabsInTheDescription() {
        assertAccepted(new MovieRequestBuilder().description("Dòng 1\r\n\tDòng 2"));

        UpsertMovieRequest request = new MovieRequestBuilder()
                .title("Phim\u0000")
                .description("Mô\u0007tả")
                .genres(List.of("Hài\nHước"))
                .people(List.of(new MovieCreditInput(null, "Trấn\tThành", CreditRole.ACTOR)))
                .build();

        assertThat(fieldErrors(request)).containsExactly(
                tuple("description", "must not contain control characters"),
                tuple("genres[0]", "must not contain control characters"),
                tuple("people[0].name", "must not contain control characters"),
                tuple("title", "must not contain control characters")
        );
        assertThat(fieldErrors(new MovieRequestBuilder().title("Tab\tinside").build()))
                .containsExactly(tuple("title", "must not contain control characters"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Dat-rung", " dat-rung", "dat-rung ", "dat--rung", "-dat", "dat-", "đất", "dat_rung", "dat\u0000"})
    void slugMustMatchThePatternAsGiven(String slug) {
        assertThat(fieldErrors(new MovieRequestBuilder().slug(slug).build()))
                .containsExactly(tuple("slug", "must match \"^[a-z0-9]+(-[a-z0-9]+)*$\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "9", "phim-2023", "dat-rung-phuong-nam"})
    void validSlugsAreKeptUnchanged(String slug) {
        assertThat(MovieRequestValidator.validate(new MovieRequestBuilder().slug(slug).build()).slug()).isEqualTo(slug);
    }

    @Test
    void genresMustHaveOneToTenEntries() {
        assertAccepted(new MovieRequestBuilder().genres(numbered("Thể loại", 10)));

        assertThat(fieldErrors(new MovieRequestBuilder().genres(List.of()).build()))
                .containsExactly(tuple("genres", "size must be between 1 and 10"));
        assertThat(fieldErrors(new MovieRequestBuilder().genres(numbered("Thể loại", 11)).build()))
                .containsExactly(tuple("genres", "size must be between 1 and 10"));
    }

    @Test
    void genreEntriesMustBePresentAndYieldASlug() {
        UpsertMovieRequest request = new MovieRequestBuilder().genres(Arrays.asList("Hài", null, "!!! ?")).build();

        assertThat(fieldErrors(request)).containsExactly(
                tuple("genres[1]", "must not be null"),
                tuple("genres[2]", "must contain at least one letter or digit")
        );
    }

    @Test
    void genresWithTheSameSlugInOneRequestAreRejected() {
        UpsertMovieRequest request = new MovieRequestBuilder().genres(List.of("Hài", " hai ", "HÀI", "Hành Động")).build();

        assertThat(fieldErrors(request)).containsExactly(
                tuple("genres[1]", "must not name the same genre as another entry"),
                tuple("genres[2]", "must not name the same genre as another entry")
        );
    }

    @Test
    void peopleMayHaveAtMostFiftyEntries() {
        List<MovieCreditInput> fifty = numbered("Người", 50).stream()
                .map(name -> new MovieCreditInput(null, name, CreditRole.ACTOR))
                .toList();
        List<MovieCreditInput> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(new MovieCreditInput(PERSON_ID, null, CreditRole.DIRECTOR));

        assertThat(MovieRequestValidator.validate(new MovieRequestBuilder().people(fifty).build()).people()).hasSize(50);
        assertThat(fieldErrors(new MovieRequestBuilder().people(fiftyOne).build()))
                .containsExactly(tuple("people", "size must be between 0 and 50"));
    }

    @Test
    void eachCreditNeedsARoleAndAPersonIdOrAName() {
        UpsertMovieRequest request = new MovieRequestBuilder()
                .people(Arrays.asList(
                        new MovieCreditInput(PERSON_ID, null, CreditRole.DIRECTOR),
                        null,
                        new MovieCreditInput(PERSON_ID, null, null),
                        new MovieCreditInput(null, null, CreditRole.ACTOR)
                ))
                .build();

        assertThat(fieldErrors(request)).containsExactly(
                tuple("people[1]", "must not be null"),
                tuple("people[2].role", "must not be null"),
                tuple("people[3]", "must have a personId or a name")
        );
    }

    @Test
    void messagesNeverContainTheRejectedValue() {
        String secret = "secret-value";
        UpsertMovieRequest request = new MovieRequestBuilder()
                .title(secret + "\u0000" + "x".repeat(10))
                .slug(secret.toUpperCase())
                .description(secret + "\u0000")
                .genres(List.of(secret + "\n"))
                .people(List.of(new MovieCreditInput(null, secret + "\u0000", CreditRole.ACTOR)))
                .build();

        List<ApiFieldError> errors = catchThrowableOfType(
                CatalogValidationException.class, () -> MovieRequestValidator.validate(request)).getFieldErrors();

        assertThat(errors).hasSize(5);
        assertThat(errors).allSatisfy(error -> assertThat(error.message()).doesNotContainIgnoringCase(secret));
        assertThat(catchThrowableOfType(CatalogValidationException.class, () -> MovieRequestValidator.validate(request)).getMessage())
                .doesNotContainIgnoringCase(secret);
    }

    private static void assertAccepted(MovieRequestBuilder builder) {
        UpsertMovieRequest request = builder.build();
        assertThat(MovieRequestValidator.validate(request)).isNotNull();
    }

    private static List<Tuple> fieldErrors(UpsertMovieRequest request) {
        CatalogValidationException exception = catchThrowableOfType(
                CatalogValidationException.class, () -> MovieRequestValidator.validate(request));
        assertThat(exception).as("validation failure").isNotNull();
        return exception.getFieldErrors().stream().map(error -> tuple(error.field(), error.message())).toList();
    }

    private static List<String> numbered(String prefix, int count) {
        List<String> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            values.add(prefix + " " + index);
        }
        return Collections.unmodifiableList(values);
    }
}
