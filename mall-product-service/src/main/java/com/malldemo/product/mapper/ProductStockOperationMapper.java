package com.malldemo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.malldemo.product.entity.ProductStockOperation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductStockOperationMapper
        extends BaseMapper<ProductStockOperation> {
}