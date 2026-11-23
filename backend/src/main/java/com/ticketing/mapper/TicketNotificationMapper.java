package com.ticketing.mapper;

import com.ticketing.vo.TicketNotificationView;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketNotificationMapper {
    @Insert("""
            INSERT INTO ticket_notification(user_id, order_id, kind, mail_next_at, mail_status)
            VALUES(#{userId}, #{orderId}, 'WAITLIST_ALLOCATED',
              IF(#{emailEnabled}, UTC_TIMESTAMP(6), NULL), IF(#{emailEnabled}, 'PENDING', NULL))
            ON DUPLICATE KEY UPDATE id=ticket_notification.id
            """)
    void waitlistAllocated(@Param("userId") long userId, @Param("orderId") long orderId,
            @Param("emailEnabled") boolean emailEnabled);

    @Insert("""
            INSERT INTO ticket_notification(user_id, order_id, kind)
            VALUES(#{userId}, #{orderId}, 'SESSION_CANCELLED')
            ON DUPLICATE KEY UPDATE id=ticket_notification.id
            """)
    void sessionCancelled(@Param("userId") long userId, @Param("orderId") long orderId);

    String LIVE_PAYMENT = """
            o.status='PENDING' AND o.expires_at>UTC_TIMESTAMP(6)
            AND a.status='PUBLISHED' AND s.status='SCHEDULED' AND s.ends_at>UTC_TIMESTAMP(6)
            """;

    @Select("""
            <script>
            SELECT CAST(n.id AS CHAR) AS id, n.kind, CAST(n.order_id AS CHAR) AS order_id,
              a.title AS activity_title, s.title AS session_title, n.created_at,n.read_at,
              IF(n.kind='WAITLIST_ALLOCATED', o.expires_at, NULL) AS payment_deadline,
              (n.kind='WAITLIST_ALLOCATED' AND (""" + LIVE_PAYMENT + """
              )) AS payment_available
            FROM ticket_notification n
            JOIN ticket_order o ON o.id=n.order_id
            JOIN activity_session s ON s.id=o.session_id
            JOIN activity a ON a.id=s.activity_id
            WHERE n.user_id=#{userId}
            <if test="unread">AND n.read_at IS NULL</if>
            ORDER BY n.id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<TicketNotificationView> list(@Param("userId") long userId, @Param("unread") boolean unread,
            @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT COUNT(*) FROM ticket_notification
            WHERE user_id=#{userId} AND (NOT #{unread} OR read_at IS NULL)
            """)
    long count(@Param("userId") long userId, @Param("unread") boolean unread);

    @Update("""
            UPDATE ticket_notification SET read_at=COALESCE(read_at, UTC_TIMESTAMP(6))
            WHERE user_id=#{userId} AND id=#{id}
            """)
    void markRead(@Param("userId") long userId, @Param("id") long id);

    @Update("""
            UPDATE ticket_notification SET read_at=UTC_TIMESTAMP(6)
            WHERE user_id=#{userId} AND read_at IS NULL
            """)
    void markAllRead(long userId);

    @Select("""
            SELECT n.id, IF((
            """ + LIVE_PAYMENT + """
            ) AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL, u.email, NULL) AS email,
              o.order_no, a.title AS activity_title, o.expires_at AS payment_deadline
            FROM ticket_notification n
            JOIN ticket_order o ON o.id=n.order_id
            JOIN activity_session s ON s.id=o.session_id
            JOIN activity a ON a.id=s.activity_id
            JOIN user_account u ON u.id=n.user_id
            WHERE n.mail_next_at<=UTC_TIMESTAMP(6)
            ORDER BY n.mail_next_at,n.id LIMIT #{size}
            """)
    List<Mail> dueMail(int size);

    @Update("""
            UPDATE ticket_notification SET mail_status=#{status}, mail_next_at=NULL
            WHERE id=#{id} AND mail_status='PENDING'
            """)
    void finishMail(@Param("id") long id, @Param("status") String status);

    @Update("""
            UPDATE ticket_notification SET
              mail_next_at=IF(mail_attempts+1>=#{maxAttempts}, NULL, TIMESTAMPADD(MINUTE,1,UTC_TIMESTAMP(6))),
              mail_status=IF(mail_attempts+1>=#{maxAttempts}, 'FAILED', 'PENDING'),
              mail_attempts=mail_attempts+1
            WHERE id=#{id} AND mail_status='PENDING'
            """)
    void retryMail(@Param("id") long id, @Param("maxAttempts") int maxAttempts);

    record Mail(long id, String email, String orderNo, String activityTitle, Instant paymentDeadline) {}
}
