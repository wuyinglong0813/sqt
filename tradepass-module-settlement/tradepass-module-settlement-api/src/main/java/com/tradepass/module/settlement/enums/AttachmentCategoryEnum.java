package com.tradepass.module.settlement.enums;

import java.util.Arrays;

/**
 * 合同附件类别。
 */
public enum AttachmentCategoryEnum {
    PAYMENT_VOUCHER("PAYMENT_VOUCHER"),
    INVOICE("INVOICE"),
    OTHER("OTHER");

    private final String category;

    AttachmentCategoryEnum(String category) {
        this.category = category;
    }

    public String getCategory() {
        return category;
    }

    public static AttachmentCategoryEnum of(String category) {
        return Arrays.stream(values())
                .filter(item -> item.category.equals(category))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown attachment category: " + category));
    }
}
