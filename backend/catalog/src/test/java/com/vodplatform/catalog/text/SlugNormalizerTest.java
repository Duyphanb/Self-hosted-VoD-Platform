package com.vodplatform.catalog.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.text.Normalizer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SlugNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Đất Rừng Phương Nam|dat-rung-phuong-nam",
            "Hành Động|hanh-dong",
            "Khoa học viễn tưởng|khoa-hoc-vien-tuong",
            "Tâm Lý - Tình Cảm|tam-ly-tinh-cam",
            "Nguyễn Thị Ánh Viên|nguyen-thi-anh-vien",
            "Trấn Thành|tran-thanh",
            "ĐÀ LẠT|da-lat",
            "đđ ĐĐ|dd-dd",
            "Sci-Fi & Fantasy|sci-fi-fantasy",
            "Top 10 Phim 2024|top-10-phim-2024",
            "  --Hài__Hước!!  |hai-huoc",
            "Amélie 東京 Ωmega|amelie-mega",
            "Straße|stra-e",
            "İstanbul|istanbul",
            "hanh-dong|hanh-dong"
    })
    void appliesTheAdr009SlugRule(String value, String expected) {
        assertThat(SlugNormalizer.slugify(value)).isEqualTo(expected);
        assertThat(SlugNormalizer.isSlug(expected)).isTrue();
    }

    @Test
    void namesThatDifferOnlyByCaseSpacingOrDiacriticsShareOneSlug() {
        assertThat(SlugNormalizer.slugify("Hài")).isEqualTo("hai");
        assertThat(SlugNormalizer.slugify("Hai")).isEqualTo("hai");
        assertThat(SlugNormalizer.slugify("  HÀI  ")).isEqualTo("hai");
        assertThat(SlugNormalizer.slugify("Hành   động")).isEqualTo(SlugNormalizer.slugify("hành động"));
    }

    @Test
    void precomposedAndDecomposedInputGiveTheSameSlug() {
        String precomposed = Normalizer.normalize("Phương Nam", Normalizer.Form.NFC);
        String decomposed = Normalizer.normalize("Phương Nam", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo(precomposed);
        assertThat(SlugNormalizer.slugify(decomposed)).isEqualTo(SlugNormalizer.slugify(precomposed))
                .isEqualTo("phuong-nam");
    }

    @Test
    void slugifyIsIdempotent() {
        String slug = SlugNormalizer.slugify("Đất Rừng Phương Nam (2023)");

        assertThat(SlugNormalizer.slugify(slug)).isEqualTo(slug).isEqualTo("dat-rung-phuong-nam-2023");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "!!!", "東京", "😀", "\u0000\u001F", "̣́"})
    void returnsAnEmptySlugWhenNothingRemains(String value) {
        assertThat(SlugNormalizer.slugify(value)).isEmpty();
        assertThat(SlugNormalizer.isSlug("")).isFalse();
    }

    @Test
    void controlCharactersAndSupplementaryCharactersActAsSeparators() {
        assertThat(SlugNormalizer.slugify("a\u0000b\tc")).isEqualTo("a-b-c");
        assertThat(SlugNormalizer.slugify("phim😀hay")).isEqualTo("phim-hay");
    }

    @Test
    void noSingleBmpCharacterProducesMoreThanOneSlugCharacter() {
        assertThat(IntStream.rangeClosed(0, 0xFFFF)
                .filter(codePoint -> !Character.isSurrogate((char) codePoint))
                .filter(codePoint -> SlugNormalizer.slugify(Character.toString(codePoint)).length() > 1)
                .boxed()
                .toList())
                .isEmpty();
    }

    @Test
    void aGenreNameAtTheContractLimitFitsTheGenreSlugColumn() {
        String name = "Đường ".repeat(16) + "Phố"; // 99 characters
        String longest = "a".repeat(100);

        assertThat(name).hasSizeLessThanOrEqualTo(100);
        assertThat(SlugNormalizer.slugify(name)).hasSizeLessThanOrEqualTo(name.length());
        assertThat(SlugNormalizer.slugify(longest)).hasSize(100).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    void recognizesOnlyTheContractSlugPattern() {
        assertThat(SlugNormalizer.isSlug("dat-rung-phuong-nam")).isTrue();
        assertThat(SlugNormalizer.isSlug("a")).isTrue();
        assertThat(SlugNormalizer.isSlug("2024")).isTrue();
        assertThat(SlugNormalizer.isSlug("a".repeat(SlugNormalizer.MOVIE_SLUG_MAX_LENGTH + 1))).isTrue();

        assertThat(SlugNormalizer.isSlug("Dat-Rung")).isFalse();
        assertThat(SlugNormalizer.isSlug("-dat")).isFalse();
        assertThat(SlugNormalizer.isSlug("dat-")).isFalse();
        assertThat(SlugNormalizer.isSlug("dat--rung")).isFalse();
        assertThat(SlugNormalizer.isSlug("dat rung")).isFalse();
        assertThat(SlugNormalizer.isSlug("đất")).isFalse();
        assertThat(SlugNormalizer.isSlug("dat_rung")).isFalse();
        assertThat(SlugNormalizer.isSlug("dat\n")).isFalse();
    }

    @Test
    void movieSlugsAreLimitedToTheContractMaximumLength() {
        String longest = "a".repeat(SlugNormalizer.MOVIE_SLUG_MAX_LENGTH - 2) + "-b";

        assertThat(SlugNormalizer.MOVIE_SLUG_MAX_LENGTH).isEqualTo(255);
        assertThat(SlugNormalizer.isMovieSlug(longest)).isTrue();
        assertThat(SlugNormalizer.isMovieSlug("dat-rung-phuong-nam")).isTrue();

        assertThat(SlugNormalizer.isMovieSlug(longest + "c")).isFalse();
        assertThat(SlugNormalizer.isMovieSlug("")).isFalse();
        assertThat(SlugNormalizer.isMovieSlug("Dat-Rung")).isFalse();
        assertThat(SlugNormalizer.isMovieSlug(" dat-rung")).isFalse();
    }

    @Test
    void personSlugBaseUsesTheSlugOfTheName() {
        assertThat(SlugNormalizer.personSlugBase("Trấn Thành")).isEqualTo("tran-thanh");
    }

    @ParameterizedTest
    @ValueSource(strings = {"東京", "!!!", "😀"})
    void personSlugBaseFallsBackWhenTheNameYieldsNothing(String name) {
        assertThat(SlugNormalizer.personSlugBase(name)).isEqualTo(SlugNormalizer.PERSON_SLUG_FALLBACK_BASE)
                .isEqualTo("person");
    }

    @Test
    void personSlugBaseIsTruncatedToTheMaximumLength() {
        assertThat(SlugNormalizer.personSlugBase("a".repeat(300))).isEqualTo("a".repeat(240));
        assertThat(SlugNormalizer.personSlugBase("a".repeat(240))).isEqualTo("a".repeat(240));
        assertThat(SlugNormalizer.PERSON_SLUG_BASE_MAX_LENGTH).isEqualTo(240);
    }

    @Test
    void personSlugBaseDropsAHyphenLeftAtTheTruncationPoint() {
        String name = "a".repeat(239) + " Nguyễn";

        String base = SlugNormalizer.personSlugBase(name);

        assertThat(base).isEqualTo("a".repeat(239));
        assertThat(SlugNormalizer.isSlug(base)).isTrue();
        assertThat(SlugNormalizer.isSlug(base + "-2")).isTrue();
    }

    @Test
    void requiresAValue() {
        assertThatNullPointerException().isThrownBy(() -> SlugNormalizer.slugify(null));
        assertThatNullPointerException().isThrownBy(() -> SlugNormalizer.isSlug(null));
        assertThatNullPointerException().isThrownBy(() -> SlugNormalizer.isMovieSlug(null));
        assertThatNullPointerException().isThrownBy(() -> SlugNormalizer.personSlugBase(null));
    }
}
