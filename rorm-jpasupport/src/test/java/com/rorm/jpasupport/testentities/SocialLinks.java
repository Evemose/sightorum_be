package com.rorm.jpasupport.testentities;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class SocialLinks {

    private String twitter;
    private String linkedin;
    private String github;
}
