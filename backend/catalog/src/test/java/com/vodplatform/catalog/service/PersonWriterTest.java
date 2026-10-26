package com.vodplatform.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vodplatform.catalog.persistence.Person;
import com.vodplatform.catalog.persistence.PersonRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The slug choice of {@link PersonWriter} and its bounded retries, without a database.
 */
class PersonWriterTest {

    @Test
    void theBaseIsUsedWhileItIsFree() {
        assertThat(PersonWriter.firstFreeSlug("tran-thanh", List.of(), null)).isEqualTo("tran-thanh");
        assertThat(PersonWriter.firstFreeSlug("tran-thanh", List.of("tran-thanh-2", "tran-thanh-jr"), null))
                .isEqualTo("tran-thanh");
    }

    @Test
    void aTakenBaseGetsTheSmallestFreeSuffixFromTwo() {
        assertThat(PersonWriter.firstFreeSlug("tran-thanh", List.of("tran-thanh"), null)).isEqualTo("tran-thanh-2");
        assertThat(PersonWriter.firstFreeSlug("tran-thanh", List.of("tran-thanh", "tran-thanh-2", "tran-thanh-4"), null))
                .isEqualTo("tran-thanh-3");
        assertThat(PersonWriter.firstFreeSlug("tran-thanh", List.of("tran-thanh-1", "tran-thanh", "tran-thanh-2"), null))
                .isEqualTo("tran-thanh-3");
    }

    @Test
    void tailsThatAreNotGeneratedSuffixesDoNotCollide() {
        List<String> taken = List.of("tran-thanh", "tran-thanh-02", "tran-thanh-2x", "tran-thanh-jr", "tran-thanh-",
                "tran-thanh-99999999999");

        assertThat(PersonWriter.firstFreeSlug("tran-thanh", taken, null)).isEqualTo("tran-thanh-2");
    }

    @Test
    void theRenamedPersonsOwnSlugIsNotACollision() {
        assertThat(PersonWriter.firstFreeSlug("ly-hai", List.of("ly-hai", "ly-hai-2"), "ly-hai-2")).isEqualTo("ly-hai-2");
        assertThat(PersonWriter.firstFreeSlug("ly-hai", List.of("ly-hai", "ly-hai-2"), "ly-hai")).isEqualTo("ly-hai");
    }

    @Test
    void createGivesUpAfterTheMaximumNumberOfTakenSlugs() {
        PersonRepository repository = mock(PersonRepository.class);
        when(repository.findSlugsWithBase("ly-hai")).thenReturn(List.of());
        when(repository.insertIfSlugFree(any(UUID.class), anyString(), anyString())).thenReturn(0);

        assertThatIllegalStateException().isThrownBy(() -> new PersonWriter(repository).create("Lý Hải"));
        verify(repository, times(PersonWriter.MAX_ATTEMPTS)).insertIfSlugFree(any(UUID.class), anyString(), anyString());
    }

    @Test
    void renameGivesUpAfterTheMaximumNumberOfTakenSlugs() {
        PersonRepository repository = mock(PersonRepository.class);
        Person person = new Person(new UUID(0L, 1), "Bob", "bob");
        when(repository.findSlugsWithBase("ly-hai")).thenReturn(List.of());
        when(repository.renameIfSlugFree(any(Person.class), anyString(), anyString())).thenReturn(false);

        assertThatIllegalStateException().isThrownBy(() -> new PersonWriter(repository).rename(person, "Lý Hải"));
        verify(repository, times(PersonWriter.MAX_ATTEMPTS)).renameIfSlugFree(any(Person.class), anyString(), anyString());
    }
}
