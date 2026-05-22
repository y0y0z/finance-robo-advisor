package org.example.finance.service;

import org.example.finance.constant.DcaStatus;
import org.example.finance.model.DcaPlan;
import org.example.finance.repository.DcaPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 定投自动执行调度器
 *
 * 每天 21:30 扫描所有 ACTIVE 计划，对到期计划自动抓取当日确认净值并执行。
 * 若净值尚未公布（如 QDII 基金），当天跳过，次日 9:00 再试一次。
 */
@Service
public class DcaSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DcaSchedulerService.class);

    /** 允许净值日期最多落后今天几天（兼容节假日/QDII 延迟） */
    private static final int MAX_NAV_LAG_DAYS = 3;

    /** 单个计划单次调度最多补执行期数，防止异常情况下无限循环 */
    private static final int MAX_CATCHUP_PER_PLAN = 365;

    private final DcaPlanRepository planRepo;
    private final DcaService        dcaService;
    private final NavFetchService   navFetchService;

    public DcaSchedulerService(DcaPlanRepository planRepo,
                               DcaService dcaService,
                               NavFetchService navFetchService) {
        this.planRepo = planRepo;
        this.dcaService = dcaService;
        this.navFetchService = navFetchService;
    }

    /** 主触发：每天 21:30（场内基金当日净值通常已公布） */
    @Scheduled(cron = "${schedule.dca.evening-cron}")
    public void runEvening() {
        log.info("[定投调度] 21:30 开始执行");
        run();
    }

    /** 补触发：每天 09:00（兜底 QDII 基金，前一日净值通常已发布） */
    @Scheduled(cron = "${schedule.dca.morning-cron}")
    public void runMorning() {
        log.info("[定投调度] 09:00 补执行（覆盖昨晚未抓到净值的计划）");
        run();
    }

    /** 手动触发：供 Controller 调用，补执行漏掉的到期计划 */
    public void runNow() {
        log.info("[定投调度] 手动触发执行");
        run();
    }

    private void run() {
        LocalDate today = LocalDate.now();

        List<DcaPlan> activePlans = planRepo.findByStatus(DcaStatus.ACTIVE);

        List<DcaPlan> duePlans = activePlans.stream()
                .filter(dcaService::isDue)
                .toList();

        if (duePlans.isEmpty()) {
            log.info("[定投调度] 无到期计划，退出");
            return;
        }

        log.info("[定投调度] 共 {} 个计划待执行", duePlans.size());

        for (DcaPlan plan : duePlans) {
            try {
                NavFetchService.FundNav fundNav = navFetchService.fetchNav(plan.getCode());

                if (fundNav == null) {
                    log.warn("[定投调度] 计划[{}] {} 净值抓取失败，跳过", plan.getId(), plan.getName());
                    continue;
                }

                long lag = ChronoUnit.DAYS.between(fundNav.navDate(), today);
                if (lag > MAX_NAV_LAG_DAYS) {
                    log.warn("[定投调度] 计划[{}] {} 净值日期 {} 过旧（差 {} 天），跳过",
                            plan.getId(), plan.getName(), fundNav.navDate(), lag);
                    continue;
                }

                int executed = 0;
                while (dcaService.isDue(plan) && executed < MAX_CATCHUP_PER_PLAN) {
                    LocalDate dueDate = dcaService.nextDueDate(plan);
                    dcaService.execute(
                            plan,
                            plan.getAmount(),
                            fundNav.nav(),
                            dueDate,
                            "自动定投" + (executed > 0 ? "（补执行）" : ""),
                            plan.getUser(),
                            true
                    );
                    executed++;
                }

                log.info("[定投调度] 计划[{}] {} 执行成功：共 {} 期，净值 {} 日期 {}",
                        plan.getId(), plan.getName(), executed, fundNav.nav(), fundNav.navDate());

            } catch (Exception e) {
                log.error("[定投调度] 计划[{}] {} 执行异常: {}",
                        plan.getId(), plan.getName(), e.getMessage(), e);
            }
        }
    }
}
