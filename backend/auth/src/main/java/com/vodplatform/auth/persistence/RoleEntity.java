package com.vodplatform.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Short id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    protected RoleEntity() {
    }

    RoleEntity(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
