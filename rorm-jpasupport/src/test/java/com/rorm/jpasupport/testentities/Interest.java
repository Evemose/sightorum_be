package com.rorm.jpasupport.testentities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "interests")
@Getter
@Setter
@SuppressWarnings("JpaDataSourceORMInspection")
public class Interest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String category;

    @ManyToMany(mappedBy = "interests")
    private Set<Customer> customers = new HashSet<>();
}
