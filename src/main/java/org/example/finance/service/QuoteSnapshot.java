package org.example.finance.service;

import java.math.BigDecimal;

public record QuoteSnapshot(
        BigDecimal price,
        BigDecimal priceChange,
        BigDecimal changePercent,
        BigDecimal pe,
        BigDecimal pb,
        String name
) {
}
