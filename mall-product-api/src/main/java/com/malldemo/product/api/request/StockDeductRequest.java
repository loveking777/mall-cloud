package com.malldemo.product.api.request;

public class StockDeductRequest {

    private String requestId;
    private Integer quantity;

    public StockDeductRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}