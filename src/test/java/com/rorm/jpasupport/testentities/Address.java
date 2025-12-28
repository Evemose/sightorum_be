package com.rorm.jpasupport.testentities;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Address {

    private String street;
    private String city;
    private String state;
    private String zipCode;
    private String country;
}
