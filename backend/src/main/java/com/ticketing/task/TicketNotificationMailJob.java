package com.ticketing.task;

import com.ticketing.mapper.TicketNotificationMapper;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ticketing.notifications.mail-enabled", havingValue = "true")
public class TicketNotificationMailJob {
    private static final Logger LOG = LoggerFactory.getLogger(TicketNotificationMailJob.class);
    private static final int BATCH_SIZE = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final DateTimeFormatter BEIJING_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final TicketNotificationMapper notifications;
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;
    private final String host;

    public TicketNotificationMailJob(TicketNotificationMapper notifications, ObjectProvider<JavaMailSender> senders,
            @Value("${ticketing.registration.mail-from:}") String from,
            @Value("${spring.mail.host:}") String host) {
        this.notifications = notifications;
        this.senders = senders;
        this.from = from;
        this.host = host;
    }

    @Scheduled(fixedDelay = 60_000)
    public void deliver() {
        var sender = senders.getIfAvailable();
        if (sender == null || from.isBlank() || host.isBlank()) {
            LOG.warn("候补提醒邮件尚未配置 SMTP，等待下一轮");
            return;
        }
        for (var task : notifications.dueMail(BATCH_SIZE)) {
            if (task.email() == null) {
                notifications.finishMail(task.id(), "SKIPPED");
                continue;
            }
            var message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(task.email());
            message.setSubject("票遇 · 候补成功，请及时支付");
            message.setText("你候补的「" + task.activityTitle() + "」已分配门票。\n订单号 " + task.orderNo()
                    + "\n支付截止 " + BEIJING_TIME.format(task.paymentDeadline())
                    + "（北京时间）\n请登录票遇，在消息中心打开订单并完成支付。");
            try {
                sender.send(message);
            } catch (MailException exception) {
                notifications.retryMail(task.id(), MAX_ATTEMPTS);
                LOG.warn("候补邮件发送失败，消息 {}，异常类型 {}", task.id(), exception.getClass().getSimpleName());
                continue;
            }
            notifications.finishMail(task.id(), "SENT");
        }
    }
}
