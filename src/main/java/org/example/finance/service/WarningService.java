package org.example.finance.service;

import org.example.finance.model.User;
import org.example.finance.model.Warning;
import org.example.finance.repository.WarningRepository;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class WarningService {
    private final WarningRepository warningRepository;

    public WarningService(WarningRepository warningRepository) {
        this.warningRepository = warningRepository;
    }

    // 获取指定用户的所有预警
    public List<Warning> getWarningsByUser(User user) {
        return warningRepository.findByUser(user);
    }

    // 获取指定用户的已触发预警（用于仪表盘红色高亮）
    public List<Warning> getTriggeredWarningsByUser(User user) {
        return warningRepository.findByUserAndStatusIn(user,
                List.of("WARNING", "PROFIT", "LOSS"));
    }

    // 根据ID获取预警
    public Warning getWarningById(Long id) {
        return warningRepository.findById(id).orElse(null);
    }

    // 保存预警
    public Warning saveWarning(Warning warning) {
        if (warning.getId() == null) {
            warning.setCreateTime(new Date());
            warning.setStatus("ACTIVE"); // 新建默认监控中
        }
        warning.setUpdateTime(new Date());
        return warningRepository.save(warning);
    }

    // 删除预警
    public void deleteWarning(Long id) {
        warningRepository.deleteById(id);
    }

    // 用户确认处理预警（将状态重置为 RESOLVED）
    public void resolveWarning(Long id) {
        Warning warning = warningRepository.findById(id).orElse(null);
        if (warning != null) {
            warning.setStatus("RESOLVED");
            warning.setUpdateTime(new Date());
            warningRepository.save(warning);
        }
    }

    // 重新激活预警（将状态重置为 ACTIVE，继续监控）
    public void reactivateWarning(Long id) {
        Warning warning = warningRepository.findById(id).orElse(null);
        if (warning != null) {
            warning.setStatus("ACTIVE");
            warning.setTriggeredPrice(null);
            warning.setTriggeredTime(null);
            warning.setUpdateTime(new Date());
            warningRepository.save(warning);
        }
    }

    // 获取状态文本（用于页面展示）
    public static String getStatusLabel(String status) {
        return switch (status) {
            case "ACTIVE"   -> "监控中";
            case "WARNING"  -> "价格预警";
            case "PROFIT"   -> "止盈触发";
            case "LOSS"     -> "止损触发";
            case "RESOLVED" -> "已处理";
            default         -> status;
        };
    }
}
