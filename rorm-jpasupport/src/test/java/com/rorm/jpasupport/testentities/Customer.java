package com.rorm.jpasupport.testentities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("JpaDataSourceORMInspection")
@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;

    private String lastName;

    private String email;

    @Enumerated(EnumType.STRING)
    private CustomerStatus status;

    private LocalDate registrationDate;

    @Embedded
    private Address shippingAddress;

    @OneToOne(mappedBy = "customer")
    private CustomerProfile profile;

    @OneToMany(mappedBy = "customer")
    private List<Order> orders = new ArrayList<>();

    @ManyToMany
    @JoinTable(
        name = "customer_interests",
        joinColumns = @JoinColumn(name = "customer_id"),
        inverseJoinColumns = @JoinColumn(name = "interest_id")
    )
    private Set<Interest> interests = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "customer_phone_numbers")
    @Column(name = "phone_number")
    private List<String> phoneNumbers = new ArrayList<>();
}
