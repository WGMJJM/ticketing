package com.ticketing.mapper;

import com.ticketing.dto.AuditQuery;
import com.ticketing.dto.AuditView;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditMapper {
    @Select(
            "SELECT id FROM user_account WHERE id=#{id} AND role='ADMIN' AND status='ACTIVE' FOR UPDATE")
    Long lockAdministrator(long id);

    @Insert(
            """
            INSERT INTO admin_audit_log(operator_id,action,resource_type,resource_id,summary,before_values,after_values,request_id)
            VALUES(#{operatorId},#{action},#{type},#{resourceId},#{summary},CAST(#{before} AS JSON),CAST(#{after} AS JSON),#{requestId})
            """)
    int append(
            @Param("operatorId") long operatorId,
            @Param("action") String action,
            @Param("type") String type,
            @Param("resourceId") long resourceId,
            @Param("summary") String summary,
            @Param("before") String before,
            @Param("after") String after,
            @Param("requestId") String requestId);

    String FILTER =
            """
            <where>
              <if test='q.operatorId != null'>AND a.operator_id=#{q.operatorId}</if>
              <if test='q.action != null'>AND a.action=#{q.action}</if>
              <if test='q.resourceType != null'>AND a.resource_type=#{q.resourceType}</if>
              <if test='q.resourceId != null'>AND a.resource_id=#{q.resourceId}</if>
              <if test='q.fromUtc() != null'>AND a.occurred_at &gt;= #{q.fromUtc()}</if>
              <if test='q.toUtc() != null'>AND a.occurred_at &lt; #{q.toUtc()}</if>
            </where>
            """;

    @Select("<script>SELECT COUNT(*) FROM admin_audit_log a " + FILTER + "</script>")
    long count(@Param("q") AuditQuery query);

    @Select(
            """
            <script>SELECT CAST(a.id AS CHAR) id,CAST(a.operator_id AS CHAR) operator_id,u.username operator_name,
              a.action,a.resource_type,CAST(a.resource_id AS CHAR) resource_id,a.occurred_at,a.summary,
              CAST(a.before_values AS CHAR) before_values,CAST(a.after_values AS CHAR) after_values,a.request_id
            FROM admin_audit_log a JOIN user_account u ON u.id=a.operator_id
            """
                    + FILTER
                    + " ORDER BY a.occurred_at DESC,a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AuditView> list(
            @Param("q") AuditQuery query, @Param("limit") int limit, @Param("offset") long offset);
}
