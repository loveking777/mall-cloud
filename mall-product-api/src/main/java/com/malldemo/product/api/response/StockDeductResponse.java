package com.malldemo.product.api.response;

public class StockDeductResponse {

    private Long productId;
    private Integer quantity;
    private Integer remainingStock;

    public StockDeductResponse() {
    }

    public StockDeductResponse(
            Long productId,
            Integer quantity,
            Integer remainingStock) {
        this.productId = productId;
        this.quantity = quantity;
        this.remainingStock = remainingStock;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getRemainingStock() {
        return remainingStock;
    }

    public void setRemainingStock(Integer remainingStock) {
        this.remainingStock = remainingStock;
    }
}