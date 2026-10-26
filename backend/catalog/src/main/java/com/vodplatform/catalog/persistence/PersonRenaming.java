package com.vodplatform.catalog.persistence;

/**
 * Person rename with a slug that may collide with a concurrent write, as a {@link PersonRepository}
 * fragment.
 */
public interface PersonRenaming {

    /**
     * Sets the name and slug of a managed person unless another person has the slug, and refreshes
     * the person from the database on success. Must run inside a transaction.
     *
     * @param person a person loaded in the current transaction
     * @param name   the normalized new name
     * @param slug   the slug to store; may be the current one
     * @return true if the person was renamed; false if another person has the slug, in which case
     *         nothing changed and the transaction remains usable
     */
    boolean renameIfSlugFree(Person person, String name, String slug);
}
