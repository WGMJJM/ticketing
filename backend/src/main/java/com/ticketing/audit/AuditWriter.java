package com.ticketing.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.common.BusinessException;
import com.ticketing.common.PageResponse;
import com.ticketing.auth.AccountPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.UUID;

/** 显式写入业务字段，不读取请求体；业务事务失败时一同回滚。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AuditWriter {
    private static final String REQUEST_ID = AuditWriter.class.getName() + ".requestId";
    private final AuditMapper mapper;
    private final ObjectMapper json;

    public AuditWriter(AuditMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditView> list(AuditQuery query) {
        query.validateRange();
        return new PageResponse<>(
                mapper.list(
                        query,
                        query.pageSize(),
                        (long) (query.pageNumber() - 1) * query.pageSize()),
                mapper.count(query),
                query.pageNumber(),
                query.pageSize());
    }

    /** 管理写入口先锁真实操作人，再锁活动/场次/票档，不能在子资源锁后回锁用户。 */
    public long begin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Long id =
                authentication != null && authentication.getPrincipal() instanceof AccountPrincipal actor
                        ? mapper.lockAdministrator(actor.getId()) : null;
        if (id == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前账号没有管理操作权限");
        }
        return id;
    }

    public void record(
            long operatorId,
            AuditAction action,
            String type,
            long resourceId,
            Map<String, ?> before,
            Map<String, ?> after) {
        try {
            if (mapper.append(
                            operatorId,
                            action.name(),
                            type,
                            resourceId,
                            action.label(),
                            json.writeValueAsString(before),
                            json.writeValueAsString(after),
                            requestId())
                    != 1) {
                throw new IllegalStateException("管理操作审计未写入");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("管理操作审计字段无法编码", exception);
        }
    }

    private String requestId() {
        var request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        var id = request.getAttribute(REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(REQUEST_ID, id, RequestAttributes.SCOPE_REQUEST);
        }
        return id.toString();
    }
}
