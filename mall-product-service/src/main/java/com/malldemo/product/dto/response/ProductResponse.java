package com.malldemo.product.dto.response;

import com.malldemo.product.entity.Product;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductResponse {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    //private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public ProductResponse(
            Long id,
            String name,
            String description,
            BigDecimal price,
            Integer stock,
            //Integer status,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        //this.status = status;
        this.createTime = createTime;
        this.updateTime = updateTime;
    }

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                //product.getStatus(),
                product.getCreateTime(),
                product.getUpdateTime()
        );
    }
}
