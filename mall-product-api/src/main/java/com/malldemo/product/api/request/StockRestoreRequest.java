package com.malldemo.product.api.request;

public class StockRestoreRequest {

    /**
     * 本次恢复操作的唯一 ID
     */
    private String requestId;

    /**
     * 原来成功扣减库存的请求 ID
     */
    private String deductRequestId;

    private Integer quantity;

    public StockRestoreRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getDeductRequestId() {
        return deductRequestId;
    }

    public void setDeductRequestId(String deductRequestId) {
        this.deductRequestId = deductRequestId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}