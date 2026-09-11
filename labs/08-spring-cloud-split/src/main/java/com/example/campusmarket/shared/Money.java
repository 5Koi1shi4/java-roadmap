package com.example.campusmarket.shared;

/** A non-negative amount represented in fen (one hundredth of a yuan). */
public record Money(long fen) {
    public Money {
        if (fen < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
    }

    public static Money ofFen(long fen) {
        return new Money(fen);
    }

    public Money multiply(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须为正数");
        }
        return new Money(Math.multiplyExact(fen, quantity));
    }
}
