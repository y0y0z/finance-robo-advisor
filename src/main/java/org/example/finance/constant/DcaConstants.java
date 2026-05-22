package org.example.finance.constant;

import java.math.BigDecimal;

public final class DcaConstants {
    private DcaConstants() {}

    public static final int AMOUNT_SCALE = 2;
    public static final int RATE_CALC_SCALE = 4;
    public static final BigDecimal PERCENT_MULTIPLIER = BigDecimal.valueOf(100);
}
