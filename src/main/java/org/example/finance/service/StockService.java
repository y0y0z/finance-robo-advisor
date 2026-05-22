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

    public StockService(StockRepository stockRepository, MarketDataService marketDataService) {
        this.stockRepository = stockRepository;
        this.marketDataService = marketDataService;
    }

    /** 根据代码查询（跨用户，供内部定时任务使用） */
    public Stock getStockByCode(String code) {
        return stockRepository.findByCode(code);
    }

    /** 根据用户+代码查询（用户隔离版，推荐控制层使用） */
    public Stock getStockByUserAndCode(User user, String code) {
        return stockRepository.findByUserAndCode(user, code);
    }

    /** 查询某用户关注的所有股票/基金（用户隔离） */
    public List<Stock> getStocksByUser(User user) {
        return stockRepository.findByUser(user);
    }

    /** 查询所有股票/基金（内部定时任务使用） */
    public List<Stock> getAllStocks() {
        return stockRepository.findAll();
    }

    /** 保存股票/基金 */
    public void saveStock(Stock stock) {
        stockRepository.save(stock);
    }

    /** 根据ID查询 */
    public Stock getStockById(Long id) {
        return stockRepository.findById(id).orElse(null);
    }

    /** 删除 */
    public void deleteStock(Long id) {
        stockRepository.deleteById(id);
    }

    /**
     * 确保该用户的关注列表中存在指定代码的股票/基金记录。
     * 若已存在则直接返回；若不存在则创建并立即从行情 API 拉取实时价格。
     *
     * @param user 关联用户
     * @param code 股票/基金代码
     * @param name 名称（仅在新建时使用）
     * @param type 类型（股票 / 基金）
     * @return 已有或新建的 Stock 记录
     */
    public Stock ensureStockExists(User user, String code, String name, String type) {
        // 1. 先查该用户是否已有此代码的记录
        Stock existing = stockRepository.findByUserAndCode(user, code);
        if (existing != null) {
            log.debug("用户 [{}] 已有 {}({}) 的关注记录，跳过创建", user.getName(), name, code);
            return existing;
        }

        // 2. 创建新记录（价格暂置0，稍后立即拉取）
        Stock stock = new Stock();
        stock.setUser(user);
        stock.setCode(code);
        stock.setName(name);
        stock.setType(type);
        stock.setPrice(BigDecimal.ZERO);
        stock.setPriceChange(BigDecimal.ZERO);
        stock.setChangePercent(BigDecimal.ZERO);
        stock.setMarket(determineMarket(code));
        stock.setUpdateTime(new Date());
        stock.setWatchTime(new Date());   // 记录关注时间
        stockRepository.save(stock);

        // 3. 立即拉取实时行情（同步，确保价格尽快写入）
        boolean success;
        if ("基金".equals(type)) {
            success = marketDataService.fetchFundPrice(stock);
        } else {
            success = marketDataService.fetchStockPrice(stock);
        }

        if (success) {
            stock.setMarket(determineMarket(code));
            stock.setUpdateTime(new Date());
            // 记录关注时价格（作为持有期收益基准）
            stock.setWatchPrice(stock.getPrice());
            stockRepository.save(stock);
            log.info("用户 [{}] 新增关注 {}({})，实时价格已获取: {}", user.getName(), name, code, stock.getPrice());
        } else {
            log.warn("用户 [{}] 新增关注 {}({})，行情 API 暂不可用，等待下次定时更新", user.getName(), name, code);
        }

        return stock;
    }

    /** 根据 A 股代码前缀判断所属市场 */
    private String determineMarket(String code) {
        if (code == null || code.isBlank()) return "未知";
        if (code.startsWith("688"))                                        return "科创板";
        if (code.startsWith("6"))                                          return "沪市";
        if (code.startsWith("0") || code.startsWith("3"))                 return "深市";
        if (code.startsWith("5")  || code.startsWith("15") || code.startsWith("16")
                || code.startsWith("18") || code.startsWith("50")
                || code.startsWith("51") || code.startsWith("52"))        return "基金";
        return "未知";
    }
}
