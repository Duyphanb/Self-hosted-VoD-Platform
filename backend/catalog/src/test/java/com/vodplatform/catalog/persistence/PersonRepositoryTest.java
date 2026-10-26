package com.vodplatform.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.vodplatform.catalog.config.CatalogConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = CatalogConfiguration.class)
class PersonRepositoryTest {

    @Autowired
    private PersonRepository personRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void listsPeopleByNameThenIdAcrossPages() {
        persist(4, "Charlie", "charlie");
        persist(3, "Alice", "alice-2");
        persist(1, "Bob", "bob");
        persist(2, "Alice", "alice");
        flushAndClear();

        Page<Person> first = personRepository.findAllByOrderByNameAscIdAsc(PageRequest.of(0, 2));
        Page<Person> second = personRepository.findAllByOrderByNameAscIdAsc(PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getContent()).extracting(Person::getId).containsExactly(uuid(2), uuid(3));
        assertThat(second.getContent()).extracting(Person::getId).containsExactly(uuid(1), uuid(4));
    }

    @Test
    void searchMatchesASlugSubstringOrderedByNameThenId() {
        persist(1, "Nguyễn Văn A", "nguyen-van-a");
        persist(2, "Trần Nguyên", "tran-nguyen");
        persist(3, "Lê Văn B", "le-van-b");
        persist(4, "Nguyễn Văn A", "nguyen-van-a-2");
        persist(5, "Phạm Nguyên Khang", "pham-nguyen-khang");
        persist(6, "Ánh Nguyệt", "anh-nguyet");
        flushAndClear();

        Page<Person> page = personRepository.findBySlugContainingOrderByNameAscIdAsc("nguyen", PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(Person::getId).containsExactly(uuid(1), uuid(4));
        assertThat(personRepository.findBySlugContainingOrderByNameAscIdAsc("nguyen", PageRequest.of(1, 2)).getContent())
                .extracting(Person::getId).containsExactly(uuid(5), uuid(2));
        assertThat(personRepository.findBySlugContainingOrderByNameAscIdAsc("van-b", PageRequest.of(0, 10)).getContent())
                .extracting(Person::getId).containsExactly(uuid(3));
        assertThat(personRepository.findBySlugContainingOrderByNameAscIdAsc("kim", PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void nameMatchingIgnoresCaseButNotDiacritics() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Tran Thanh", "tran-thanh-2");
        persist(4, "Ngô Thanh Vân", "ngo-thanh-van-2");
        persist(3, "Ngô Thanh Vân", "ngo-thanh-van");
        flushAndClear();

        assertThat(personRepository.findAllByNameIgnoringCase("TRẤN THÀNH"))
                .extracting(Person::getId).containsExactly(uuid(1));
        assertThat(personRepository.findAllByNameIgnoringCase("trấn thành"))
                .extracting(Person::getId).containsExactly(uuid(1));
        assertThat(personRepository.findAllByNameIgnoringCase("tran thanh"))
                .extracting(Person::getId).containsExactly(uuid(2));
        assertThat(personRepository.findAllByNameIgnoringCase("ngô thanh vân"))
                .extracting(Person::getId).containsExactly(uuid(3), uuid(4));
        assertThat(personRepository.findAllByNameIgnoringCase("Ngô Thanh")).isEmpty();
    }

    @Test
    void findsTheSlugsThatCanCollideWithAGeneratedSlug() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Trấn Thành", "tran-thanh-2");
        persist(3, "Trấn Thành", "tran-thanh-10");
        persist(4, "Trấn Thành Huy", "tran-thanh-huy");
        persist(5, "Trấn Thànhx", "tran-thanhx");
        persist(6, "Lê Trấn Thành", "le-tran-thanh");
        persist(7, "Trấn", "tran");
        flushAndClear();

        assertThat(personRepository.findSlugsWithBase("tran-thanh"))
                .containsExactlyInAnyOrder("tran-thanh", "tran-thanh-2", "tran-thanh-10", "tran-thanh-huy");
        assertThat(personRepository.findSlugsWithBase("person")).isEmpty();
    }

    @Test
    void insertIfSlugFreeSkipsATakenSlugButNotATakenName() {
        persist(1, "Trấn Thành", "tran-thanh");
        flushAndClear();

        assertThat(personRepository.insertIfSlugFree(uuid(2), "Trấn Thành", "tran-thanh")).isZero();
        assertThat(personRepository.insertIfSlugFree(uuid(3), "Trấn Thành", "tran-thanh-2")).isEqualTo(1);
        entityManager.clear();

        assertThat(personRepository.findAllByNameIgnoringCase("trấn thành"))
                .extracting(Person::getId, Person::getSlug)
                .containsExactly(tuple(uuid(1), "tran-thanh"), tuple(uuid(3), "tran-thanh-2"));
    }

    @Test
    void findForUpdateLoadsThePersonWithAPessimisticWriteLock() {
        persist(1, "Lý Hải", "ly-hai");
        flushAndClear();

        Person locked = personRepository.findForUpdateById(uuid(1)).orElseThrow();

        assertThat(locked.getSlug()).isEqualTo("ly-hai");
        assertThat(entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(personRepository.findForUpdateById(uuid(2))).isEmpty();
    }

    @Test
    void renameIfSlugFreeRenamesAndRefreshesThePerson() {
        persist(1, "Tuấn Trần", "tuan-tran");
        flushAndClear();
        Person person = personRepository.findById(uuid(1)).orElseThrow();

        assertThat(personRepository.renameIfSlugFree(person, "Trấn Thành", "tran-thanh")).isTrue();

        assertThat(person.getName()).isEqualTo("Trấn Thành");
        assertThat(person.getSlug()).isEqualTo("tran-thanh");
        entityManager.clear();
        assertThat(personRepository.findById(uuid(1)).orElseThrow().getSlug()).isEqualTo("tran-thanh");
    }

    @Test
    void renameIfSlugFreeLeavesEverythingUnchangedWhenAnotherPersonHasTheSlug() {
        persist(1, "Trấn Thành", "tran-thanh");
        persist(2, "Tuấn Trần", "tuan-tran");
        flushAndClear();
        Person person = personRepository.findById(uuid(2)).orElseThrow();

        assertThat(personRepository.renameIfSlugFree(person, "Trấn Thành", "tran-thanh")).isFalse();

        assertThat(person.getName()).isEqualTo("Tuấn Trần");
        // The failed update was rolled back to its savepoint, so the transaction goes on.
        assertThat(personRepository.renameIfSlugFree(person, "Trấn Thành", "tran-thanh-2")).isTrue();
        entityManager.clear();
        assertThat(personRepository.findAll())
                .extracting(Person::getId, Person::getName, Person::getSlug)
                .containsExactlyInAnyOrder(
                        tuple(uuid(1), "Trấn Thành", "tran-thanh"),
                        tuple(uuid(2), "Trấn Thành", "tran-thanh-2"));
    }

    private void persist(int id, String name, String slug) {
        entityManager.persist(new Person(uuid(id), name, slug));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static UUID uuid(int value) {
        return new UUID(0L, value);
    }
}
