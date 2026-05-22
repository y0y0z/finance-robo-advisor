package org.example.finance.service;

import org.example.finance.model.Warning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.text.SimpleDateFormat;

/**
 * 邮件发送服务（QQ 邮箱 SMTP）
 *
 * 配置说明：
 *   1. 登录 QQ 邮箱 → 设置 → 账号 → 开启 SMTP 服务
 *   2. 生成授权码，填入 application.properties 的 spring.mail.password
 *   3. 本系统固定从 712664210@qq.com 发送预警通知到用户注册邮箱
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${warning.email.from:712664210@qq.com}")
    private String fromEmail;

    /**
     * 发送预警触发通知邮件
     *
     * @param toEmail  收件人邮箱（用户注册邮箱）
     * @param warning  已触发的预警对象
     */
    public void sendWarningEmail(String toEmail, Warning warning) {
        if (mailSender == null) {
            log.warn("JavaMailSender 未配置，跳过邮件发送。请检查 spring.mail 配置");
            return;
        }
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("用户邮箱为空，无法发送预警邮件（预警: {}({})）", warning.getName(), warning.getCode());
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(buildSubject(warning));
            helper.setText(buildHtmlBody(warning), true);

            mailSender.send(message);
            log.info("预警邮件已发送至 [{}]：{}({}) {}", toEmail,
                    warning.getName(), warning.getCode(), warning.getStatus());

        } catch (Exception e) {
            log.error("发送预警邮件失败 [{}→{}]: {}", warning.getCode(), toEmail, e.getMessage());
        }
    }

    private String buildSubject(Warning warning) {
        String typeLabel = switch (warning.getStatus()) {
            case "LOSS"    -> "【止损触发】";
            case "PROFIT"  -> "【止盈触发】";
            case "WARNING" -> "【价格预警】";
            default        -> "【预警通知】";
        };
        return typeLabel + warning.getName() + "(" + warning.getCode() + ") 触发通知";
    }

    private String buildHtmlBody(Warning warning) {
        String statusLabel = switch (warning.getStatus()) {
            case "LOSS"    -> "<span style='color:#dc3545;font-weight:bold'>止损线触发</span>";
            case "PROFIT"  -> "<span style='color:#198754;font-weight:bold'>止盈线触发</span>";
            case "WARNING" -> "<span style='color:#ffc107;font-weight:bold'>价格预警触发</span>";
            default        -> warning.getStatus();
        };

        String triggerTime = warning.getTriggeredTime() != null
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(warning.getTriggeredTime())
                : "刚刚";

        return """
                <html><body style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;">
                <div style="background:#0d6efd;color:white;padding:16px 24px;border-radius:8px 8px 0 0;">
                  <h2 style="margin:0;">📢 投资预警通知</h2>
                  <small>资产管理助手 · MicroFinance</small>
                </div>
                <div style="border:1px solid #dee2e6;border-top:none;padding:24px;border-radius:0 0 8px 8px;">
                  <table style="width:100%;border-collapse:collapse;">
                    <tr><td style="padding:8px 0;color:#6c757d;width:120px;">标的名称</td>
                        <td style="padding:8px 0;font-weight:bold;">%s（%s）%s</td></tr>
                    <tr><td style="padding:8px 0;color:#6c757d;">触发类型</td>
                        <td style="padding:8px 0;">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#6c757d;">触发价格</td>
                        <td style="padding:8px 0;font-size:1.2em;font-weight:bold;color:#dc3545;">%s 元</td></tr>
                    <tr><td style="padding:8px 0;color:#6c757d;">触发时间</td>
                        <td style="padding:8px 0;">%s</td></tr>
                    <tr><td style="padding:8px 0;color:#6c757d;">预警含义</td>
                        <td style="padding:8px 0;">%s</td></tr>
                  </table>
                  <hr style="margin:16px 0;">
                  <p style="color:#6c757d;font-size:0.85em;margin:0;">
                    请及时登录 <a href="http://localhost:8080/warnings">资产管理助手</a> 查看并处理该预警。
                    本邮件由系统自动发送，请勿直接回复。
                  </p>
                </div>
                </body></html>
                """.formatted(
                warning.getName(), warning.getCode(), warning.getType(),
                statusLabel,
                warning.getTriggeredPrice() != null ? warning.getTriggeredPrice().toPlainString() : "-",
                triggerTime,
                warning.getMeaning() != null ? warning.getMeaning() : "-"
        );
    }
}
