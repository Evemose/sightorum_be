package com.rorm.jpasupport.testentities;

import jakarta.persistence.Embeddable;
import lombok.Data;

import java.math.BigDecimal;

@Embeddable
@Data
public class OrderItem {

    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
}
