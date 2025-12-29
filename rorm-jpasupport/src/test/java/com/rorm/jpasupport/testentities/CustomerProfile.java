package com.rorm.jpasupport.testentities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "customer_profiles")
@Getter
@Setter
@SuppressWarnings("JpaDataSourceORMInspection")
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private String bio;
    private String avatarUrl;

    @Embedded
    private SocialLinks socialLinks;
}
