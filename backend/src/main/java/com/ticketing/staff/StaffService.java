package com.ticketing.staff;

import com.ticketing.audit.AuditWriter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ticketing.common.BusinessException;
import com.ticketing.common.PageResponse;
import com.ticketing.activity.Activity;
import com.ticketing.activity.ActivitySession;
import com.ticketing.activity.ActivityMapper;
import com.ticketing.activity.ActivitySessionMapper;
import com.ticketing.audit.AuditAction;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class StaffService {
    private final StaffMapper mapper;
    private final ActivityMapper activities;
    private final ActivitySessionMapper sessions;
    private final AuditWriter audit;

    public StaffService(
            StaffMapper mapper,
            ActivityMapper activities,
            ActivitySessionMapper sessions,
            AuditWriter audit) {
        this.mapper = mapper;
        this.activities = activities;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<StaffAccountView> list(StaffQuery q) {
        return new PageResponse<>(
                mapper.list(q.username(), q.pageSize(), (long) (q.pageNumber() - 1) * q.pageSize()),
                mapper.count(q.username()),
                q.pageNumber(),
                q.pageSize());
    }

    @Transactional(readOnly = true)
    public PageResponse<StaffPermissionView> permissions(long staffId, int page, int size) {
        requireAccount(staffId);
        return new PageResponse<>(
                mapper.permissions(staffId, size, (long) (page - 1) * size),
                mapper.countPermissions(staffId),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public PageResponse<StaffPermissionView> available(long staffId, int page, int size) {
        return new PageResponse<>(
                mapper.available(staffId, size, (long) (page - 1) * size),
                mapper.countAvailable(staffId),
                page,
                size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffAccountView role(long operatorId, long staffId, boolean promote) {
        var before = lockAccounts(operatorId, staffId);
        if (!"ACTIVE".equals(before.status())) {
            throw conflict("已停用账号不能变更工作人员角色");
        }
        String next = promote ? "CHECKIN_STAFF" : "USER";
        if (!next.equals(before.role())) {
            mapper.role(staffId, next);
            int revoked = promote ? 0 : mapper.revokeAll(staffId);
            audit.record(
                    operatorId,
                    promote ? AuditAction.STAFF_PROMOTE : AuditAction.STAFF_DEMOTE,
                    "STAFF",
                    staffId,
                    Map.of("role", before.role()),
                    Map.of("role", next, "revokedPermissions", revoked));
        }
        return requireAccount(staffId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffAccountView disable(long operatorId, long staffId) {
        var before = lockAccounts(operatorId, staffId);
        requireStaff(before);
        if (!"DISABLED".equals(before.status())) {
            mapper.disable(staffId);
            int revoked = mapper.revokeAll(staffId);
            audit.record(
                    operatorId,
                    AuditAction.STAFF_DISABLE,
                    "STAFF",
                    staffId,
                    Map.of("status", before.status()),
                    Map.of("status", "DISABLED", "revokedPermissions", revoked));
        }
        return requireAccount(staffId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffPermissionView grant(long operatorId, long staffId, long sessionId) {
        var staff = lockAccounts(operatorId, staffId);
        requireStaff(staff);
        if (!"ACTIVE".equals(staff.status())) {
            throw conflict("工作人员已停用，不能分配场次");
        }
        var initial = sessions.selectById(sessionId);
        if (initial == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "场次不存在");
        }
        activities.selectOne(
                new LambdaQueryWrapper<Activity>()
                        .eq(Activity::getId, initial.getActivityId())
                        .last("FOR SHARE"));
        var session =
                sessions.selectOne(
                        new LambdaQueryWrapper<ActivitySession>()
                                .eq(ActivitySession::getId, sessionId)
                                .last("FOR SHARE"));
        if (!"SCHEDULED".equals(session.getStatus())) {
            throw conflict("只能授权正常场次");
        }
        var before = mapper.permission(staffId, sessionId);
        if (before == null || before.revokedAt() != null) {
            mapper.grant(staffId, sessionId, operatorId);
            audit.record(
                    operatorId,
                    AuditAction.SESSION_GRANT,
                    "STAFF",
                    staffId,
                    Map.of("sessionId", Long.toString(sessionId), "active", false),
                    Map.of("sessionId", Long.toString(sessionId), "active", true));
        }
        return mapper.permission(staffId, sessionId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StaffPermissionView revoke(long operatorId, long staffId, long sessionId) {
        lockAccounts(operatorId, staffId);
        var before = mapper.permission(staffId, sessionId);
        if (before == null) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND", "该工作人员没有此场次授权记录");
        }
        if (mapper.revoke(staffId, sessionId) == 1) {
            audit.record(
                    operatorId,
                    AuditAction.SESSION_REVOKE,
                    "STAFF",
                    staffId,
                    Map.of("sessionId", Long.toString(sessionId), "active", true),
                    Map.of("sessionId", Long.toString(sessionId), "active", false));
        }
        return mapper.permission(staffId, sessionId);
    }

    /** 核销持工作人员账号SHARE锁，撤权持UPDATE锁；权限读取在该协调锁保护下。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requirePermission(long staffId, long sessionId) {
        if (mapper.permitted(staffId, sessionId) != 1) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN, "CHECKIN_SESSION_FORBIDDEN", "当前账号未获此场次核销授权");
        }
    }

    private StaffAccountView lockAccounts(long operatorId, long staffId) {
        // 与退款一致：涉及多个用户时按ID递增，绝不在持有场次/票券锁后回锁用户。
        var first = mapper.lockAccount(Math.min(operatorId, staffId));
        var second =
                operatorId == staffId ? first : mapper.lockAccount(Math.max(operatorId, staffId));
        var operator = operatorId <= staffId ? first : second;
        var staff = operatorId <= staffId ? second : first;
        if (operator == null
                || !"ADMIN".equals(operator.role())
                || !"ACTIVE".equals(operator.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号没有工作人员管理权限");
        }
        if (staff == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在");
        }
        if ("ADMIN".equals(staff.role())) {
            throw conflict("工作人员管理不能修改管理员账号");
        }
        return staff;
    }

    private StaffAccountView requireAccount(long id) {
        var account = mapper.account(id);
        if (account == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在");
        }
        return account;
    }

    private void requireStaff(StaffAccountView account) {
        if (!"CHECKIN_STAFF".equals(account.role())) {
            throw conflict("请先将普通账号授权为工作人员");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "STAFF_CONFLICT", message);
    }
}
