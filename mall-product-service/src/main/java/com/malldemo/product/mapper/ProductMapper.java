package com.malldemo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.malldemo.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 原子扣减库存
     */
    @Update("""
        UPDATE product
        SET stock = stock - #{quantity}
        WHERE id = #{productId}
          AND status = 1
          AND stock >= #{quantity}
        """)
    int deductStock(
            @Param("productId") Long productId,
            @Param("quantity") Integer quantity
    );

    /**
     * 恢复商品库存
     */
    @Update("""
        UPDATE product
        SET stock = stock + #{quantity}
        WHERE id = #{productId}
        """)
    int restoreStock(
            @Param("productId") Long productId,
            @Param("quantity") Integer quantity
    );
}