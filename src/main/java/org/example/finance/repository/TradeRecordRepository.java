package org.example.finance.repository;

import org.example.finance.model.Asset;
import org.example.finance.model.TradeRecord;
import org.example.finance.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRecordRepository extends JpaRepository<TradeRecord, Long> {

    /** 查某资产下所有交易，按日期倒序 */
    List<TradeRecord> findByAssetOrderByTradeDateDesc(Asset asset);

    /** 查某用户所有交易，按日期倒序（用于总览） */
    List<TradeRecord> findByUserOrderByTradeDateDesc(User user);
}
