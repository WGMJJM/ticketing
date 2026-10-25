package com.ticketing.mapper;

import com.ticketing.vo.StaffAccountView;
import com.ticketing.vo.StaffPermissionView;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface StaffMapper {
    String ACCOUNT = "SELECT CAST(id AS CHAR) id,username,role,status FROM user_account ";

    @Select(ACCOUNT + "WHERE id=#{id} FOR UPDATE")
    StaffAccountView lockAccount(long id);

    @Select(ACCOUNT + "WHERE id=#{id}")
    StaffAccountView account(long id);

    @Select(
            """
            <script>SELECT COUNT(*) FROM user_account WHERE role IN ('USER','CHECKIN_STAFF')
            <if test='username != null and username != ""'>AND username=#{username}</if></script>
            """)
    long count(String username);

    @Select(
            "<script>"
                    + ACCOUNT
                    + """
            WHERE role IN ('USER','CHECKIN_STAFF')
            <if test='username != null and username != ""'>AND username=#{username}</if>
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
            """)
    List<StaffAccountView> list(
            @Param("username") String username,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Update("UPDATE user_account SET role=#{role} WHERE id=#{id}")
    int role(@Param("id") long id, @Param("role") String role);

    @Update("UPDATE user_account SET status='DISABLED' WHERE id=#{id}")
    int disable(long id);

    @Update(
            "UPDATE staff_session_permission SET revoked_at=UTC_TIMESTAMP(6) WHERE staff_id=#{id} AND revoked_at IS NULL")
    int revokeAll(long id);

    @Select(
            "SELECT COUNT(*) FROM staff_session_permission p JOIN user_account u ON u.id=p.staff_id WHERE p.staff_id=#{staffId} AND p.session_id=#{sessionId} AND p.revoked_at IS NULL AND u.role='CHECKIN_STAFF' AND u.status='ACTIVE'")
    int permitted(@Param("staffId") long staffId, @Param("sessionId") long sessionId);

    String PERMISSIONS =
            """
            SELECT CAST(p.staff_id AS CHAR) staff_id,CAST(s.id AS CHAR) session_id,a.title activity_title,
              s.title session_title,s.starts_at,s.ends_at,s.status session_status,CAST(p.granted_by AS CHAR) granted_by,
              p.granted_at,p.revoked_at
            FROM staff_session_permission p JOIN activity_session s ON s.id=p.session_id JOIN activity a ON a.id=s.activity_id
            """;

    @Select(
            PERMISSIONS
                    + "WHERE p.staff_id=#{staffId} ORDER BY s.starts_at DESC,s.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<StaffPermissionView> permissions(
            @Param("staffId") long staffId,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM staff_session_permission WHERE staff_id=#{staffId}")
    long countPermissions(long staffId);

    @Select(PERMISSIONS + "WHERE p.staff_id=#{staffId} AND p.session_id=#{sessionId}")
    StaffPermissionView permission(
            @Param("staffId") long staffId, @Param("sessionId") long sessionId);

    @Insert(
            """
            INSERT INTO staff_session_permission(staff_id,session_id,granted_by) VALUES(#{staffId},#{sessionId},#{operatorId})
            ON DUPLICATE KEY UPDATE granted_by=#{operatorId},granted_at=UTC_TIMESTAMP(6),revoked_at=NULL
            """)
    int grant(
            @Param("staffId") long staffId,
            @Param("sessionId") long sessionId,
            @Param("operatorId") long operatorId);

    @Update(
            "UPDATE staff_session_permission SET revoked_at=UTC_TIMESTAMP(6) WHERE staff_id=#{staffId} AND session_id=#{sessionId} AND revoked_at IS NULL")
    int revoke(@Param("staffId") long staffId, @Param("sessionId") long sessionId);

    String AVAILABLE =
            """
            FROM activity_session s JOIN activity a ON a.id=s.activity_id
            WHERE s.status='SCHEDULED' AND s.ends_at>UTC_TIMESTAMP(6)
              AND EXISTS(SELECT 1 FROM user_account u WHERE u.id=#{staffId} AND u.status='ACTIVE'
                AND (u.role='ADMIN' OR (u.role='CHECKIN_STAFF' AND EXISTS(
                  SELECT 1 FROM staff_session_permission p WHERE p.staff_id=u.id AND p.session_id=s.id AND p.revoked_at IS NULL))))
            """;

    @Select("SELECT COUNT(*) " + AVAILABLE)
    long countAvailable(long staffId);

    @Select(
            """
            SELECT CAST(#{staffId} AS CHAR) staff_id,CAST(s.id AS CHAR) session_id,a.title activity_title,s.title session_title,
              s.starts_at,s.ends_at,s.status session_status,NULL granted_by,NULL granted_at,NULL revoked_at
            """
                    + AVAILABLE
                    + " ORDER BY s.starts_at,s.id LIMIT #{limit} OFFSET #{offset}")
    List<StaffPermissionView> available(
            @Param("staffId") long staffId,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
