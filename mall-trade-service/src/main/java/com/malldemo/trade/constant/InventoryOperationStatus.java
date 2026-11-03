package com.malldemo.trade.constant;

public final class InventoryOperationStatus {

    private InventoryOperationStatus() {
    }

    // 库存扣减状态
    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String REJECTED = "REJECTED";
    public static final String UNKNOWN = "UNKNOWN";

    // 库存恢复状态
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    public static final String RESTORE_PENDING = "PENDING";
    public static final String RESTORE_SUCCEEDED = "SUCCEEDED";
    public static final String RESTORE_FAILED = "FAILED";
    public static final String RESTORE_UNKNOWN = "UNKNOWN";
}