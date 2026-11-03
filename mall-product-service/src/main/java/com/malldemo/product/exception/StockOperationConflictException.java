package com.malldemo.product.exception;

public class StockOperationConflictException
        extends RuntimeException {

    public StockOperationConflictException(String message) {
        super(message);
    }
}