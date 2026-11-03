package com.malldemo.trade.common;

public final class OrderStatus {

    public static final int CREATED = 0;
    public static final int CANCELLED = 1;
    public static final int PROCESSING = 2;
    public static final int FAILED = 3;
    public static final int COMPENSATION_PENDING = 4;

    private OrderStatus() {
    }

    public static String nameOf(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }

        return switch (status) {
            case CREATED -> "CREATED";
            case CANCELLED -> "CANCELLED";
            case PROCESSING -> "PROCESSING";
            case FAILED -> "FAILED";
            case COMPENSATION_PENDING -> "COMPENSATION_PENDING";
            default -> "UNKNOWN";
        };
    }
}