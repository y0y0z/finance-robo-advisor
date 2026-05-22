package org.example.finance.service;

import org.example.finance.model.NetValueSnapshot;
import org.example.finance.model.User;
import org.example.finance.repository.NetValueSnapshotRepository;
import org.example.finance.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class NetValueSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(NetValueSnapshotService.class);

    private final NetValueSnapshotRepository snapshotRepo;
    private final UserRepository userRepository;
    private final AssetService assetService;

    public NetValueSnapshotService(NetValueSnapshotRepository snapshotRepo,
                                   UserRepository userRepository,
                                   AssetService assetService) {
        this.snapshotRepo = snapshotRepo;
        this.userRepository = userRepository;
        this.assetService = assetService;
    }

    /**
     * 定时快照：次日 09:30 执行，此时基金 T+1 净值已确认。
     * 记录日期用「昨天」，对应昨日收盘市值。
     */
    @Scheduled(cron = "${schedule.net-value-snapshot.cron}")
    public void takeSnapshot() {
        // 09:30 执行时，记录昨天的收盘市值（今天的净值还没出来）
        LocalDate recordDate = LocalDate.now().minusDays(1);
        log.info("[净值快照] 定时触发，记录日期: {}", recordDate);
        takeSnapshotForDate(recordDate);
    }

    /**
     * 应用启动后补录：检查昨天和今天有没有快照，没有则立即补录。
     * 解决应用停机期间漏掉的快照数据。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate today     = LocalDate.now();

        for (User user : userRepository.findAll()) {
            if (!assetService.hasAssets(user)) continue;

            // 补昨天
            if (snapshotRepo.findByUserAndSnapshotDate(user, yesterday).isEmpty()) {
                log.info("[净值快照] 启动补录: 用户[{}] 日期[{}]", user.getName(), yesterday);
                saveSnapshot(user, yesterday);
            }
            // 补今天（如果今天 09:30 已过但快照还没有）
            if (snapshotRepo.findByUserAndSnapshotDate(user, today).isEmpty()) {
                log.info("[净值快照] 启动补录: 用户[{}] 日期[{}]", user.getName(), today);
                saveSnapshot(user, today);
            }
        }
    }

    /** 对所有用户记录指定日期的快照 */
    private void takeSnapshotForDate(LocalDate date) {
        for (User user : userRepository.findAll()) {
            if (!assetService.hasAssets(user)) continue;
            saveSnapshot(user, date);
        }
    }

    /** 保存或更新单个用户某天的快照 */
    private void saveSnapshot(User user, LocalDate date) {
        snapshotRepo.findByUserAndSnapshotDate(user, date).ifPresentOrElse(
            s -> {
                s.setTotalValue(assetService.calculateTotalAssetValue(user));
                s.setTotalCost(assetService.calculateTotalCost(user));
                snapshotRepo.save(s);
            },
            () -> {
                NetValueSnapshot s = new NetValueSnapshot();
                s.setUser(user);
                s.setSnapshotDate(date);
                s.setTotalValue(assetService.calculateTotalAssetValue(user));
                s.setTotalCost(assetService.calculateTotalCost(user));
                snapshotRepo.save(s);
            }
        );
    }

    public List<NetValueSnapshot> getSnapshots(User user) {
        return snapshotRepo.findByUserOrderBySnapshotDateAsc(user);
    }
}

