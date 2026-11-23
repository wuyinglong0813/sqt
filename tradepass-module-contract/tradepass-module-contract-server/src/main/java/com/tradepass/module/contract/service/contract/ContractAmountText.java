package com.tradepass.module.contract.service.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ContractAmountText {
    private ContractAmountText() {
    }

    public static String uppercase(BigDecimal value) {
        BigDecimal normalized = value == null
                ? BigDecimal.ZERO : value.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        long fenValue;
        try {
            fenValue = normalized.movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            return "金额超出大写转换范围";
        }
        if (fenValue == 0) {
            return "零元整";
        }
        String[] digits = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
        String[] units = {"分", "角", "元", "拾", "佰", "仟", "万", "拾", "佰", "仟", "亿",
                "拾", "佰", "仟", "兆", "拾", "佰", "仟"};
        StringBuilder result = new StringBuilder();
        boolean pendingZero = false;
        int index = 0;
        while (fenValue > 0 && index < units.length) {
            int digit = (int) (fenValue % 10);
            if (digit == 0) {
                if (index == 2 || index == 6 || index == 10 || index == 14) {
                    if (result.isEmpty() || !result.substring(0, 1).equals(units[index])) {
                        result.insert(0, units[index]);
                    }
                }
                pendingZero = !result.isEmpty();
            } else {
                if (pendingZero && !result.isEmpty() && !result.substring(0, 1).equals("零")) {
                    result.insert(0, "零");
                }
                result.insert(0, digits[digit] + units[index]);
                pendingZero = false;
            }
            fenValue /= 10;
            index++;
        }
        String text = result.toString()
                .replaceAll("零+", "零")
                .replace("零万", "万")
                .replace("零亿", "亿")
                .replace("亿万", "亿")
                .replace("零元", "元");
        if (!text.contains("角") && !text.contains("分")) {
            text += "整";
        }
        return text;
    }
}
