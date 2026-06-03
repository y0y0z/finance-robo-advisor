package org.example.finance.service;

import org.example.finance.model.Stock;
import org.example.finance.model.User;
import org.example.finance.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stockRepository;
    private final MarketDataService marketDataService;
    private final MarketResolver marketResolver;

    public StockService(StockRepository stockRepository, MarketDataService marketDataService, MarketResolver marketResolver) {
        this.stockRepository = stockRepository;
        this.marketDataService = marketDataService;
        this.marketResolver = marketResolver;
    }

    public Stock getStockByCode(String code) {
        return stockRepository.findByCode(normalizeCode(code));
    }

    public Stock getStockByUserAndCode(User user, String code) {
        return getStockByCode(code);
    }

    public Stock getStockByUserAndCodeAndMarket(User user, String code, String market) {
        return getStockByCode(code);
    }

    public String normalizeCode(String code) {
        return marketResolver.normalizeCode(code);
    }

    public String normalizeMarket(String type, String code, String market) {
        return marketResolver.normalizeMarket(type, code, market);
    }

    public List<Stock> getStocksByUser(User user) {
        return stockRepository.findAll();
    }

    public List<Stock> getAllStocks() {
        return stockRepository.findAll();
    }

    public void saveStock(Stock stock) {
        stockRepository.save(stock);
    }

    public Stock getStockById(Long id) {
        return stockRepository.findById(id).orElse(null);
    }

    public void deleteStock(Long id) {
        stockRepository.deleteById(id);
    }

    public Stock ensureStockExists(User user, String code, String name, String type) {
        return ensureStockExists(user, code, name, type, MarketResolver.MARKET_AUTO);
    }

    public Stock ensureStockExists(User user, String code, String name, String type, String market) {
        String normalizedCode = normalizeCode(code);
        String normalizedMarket = normalizeMarket(type, normalizedCode, market);
        Stock existing = stockRepository.findByCode(normalizedCode);
        if (existing != null) {
            boolean changed = fillMissingMetadata(existing, name, type, normalizedMarket);
            if (needsPrice(existing) && fetchLatestPrice(existing)) {
                if (existing.getWatchPrice() == null) {
                    existing.setWatchPrice(existing.getPrice());
                }
                changed = true;
            }
            if (changed) {
                stockRepository.save(existing);
            }
            log.debug("复用已有行情记录 {}({})", existing.getName(), normalizedCode);
            return existing;
        }

        Stock stock = new Stock();
        stock.setCode(normalizedCode);
        stock.setName(name);
        stock.setType(type);
        stock.setPrice(BigDecimal.ZERO);
        stock.setPriceChange(BigDecimal.ZERO);
        stock.setChangePercent(BigDecimal.ZERO);
        stock.setMarket(normalizedMarket);
        stock.setUpdateTime(new Date());
        stock.setWatchTime(new Date());
        stockRepository.save(stock);

        if (fetchLatestPrice(stock)) {
            stock.setUpdateTime(new Date());
            stock.setWatchPrice(stock.getPrice());
            stockRepository.save(stock);
            log.info("新增行情记录 {}({})，实时价格已获取: {}", name, normalizedCode, stock.getPrice());
        } else {
            log.warn("新增行情记录 {}({})，行情 API 暂不可用，等待下次定时更新", name, normalizedCode);
        }

        return stock;
    }

    private boolean fillMissingMetadata(Stock stock, String name, String type, String market) {
        boolean changed = false;
        if (isBlank(stock.getName()) && !isBlank(name)) {
            stock.setName(name);
            changed = true;
        }
        if (isBlank(stock.getType()) && !isBlank(type)) {
            stock.setType(type);
            changed = true;
        }
        if (shouldFillMarket(stock.getMarket()) && !shouldFillMarket(market)) {
            stock.setMarket(market);
            changed = true;
        }
        if (stock.getUpdateTime() == null) {
            stock.setUpdateTime(new Date());
            changed = true;
        }
        if (stock.getWatchTime() == null) {
            stock.setWatchTime(new Date());
            changed = true;
        }
        return changed;
    }

    private boolean fetchLatestPrice(Stock stock) {
        if ("基金".equals(stock.getType())) {
            return marketDataService.fetchFundPrice(stock);
        }
        return marketDataService.fetchStockPrice(stock);
    }

    private boolean needsPrice(Stock stock) {
        return stock.getPrice() == null || stock.getPrice().compareTo(BigDecimal.ZERO) <= 0;
    }

    private boolean shouldFillMarket(String market) {
        return isBlank(market)
                || MarketResolver.MARKET_AUTO.equals(market)
                || MarketResolver.MARKET_UNKNOWN.equals(market);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
