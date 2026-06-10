package com.factory.repair.aop;

import com.factory.repair.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public @interface Audited {
        String entityType();
        String action();
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getMethod().getName();
        Object[] args = joinPoint.getArgs();

        Long entityId = extractEntityId(args);
        String beforeSnapshot = null;

        try {
            Object result = joinPoint.proceed();

            auditLogService.log(
                    audited.entityType(),
                    entityId,
                    audited.action(),
                    null, null,
                    beforeSnapshot,
                    safeJson(result),
                    "method=" + methodName
            );

            return result;
        } catch (Exception e) {
            auditLogService.log(
                    audited.entityType(),
                    entityId,
                    audited.action() + "_FAILED",
                    null, null,
                    beforeSnapshot,
                    null,
                    "error=" + e.getMessage()
            );
            throw e;
        }
    }

    private Long extractEntityId(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Long) {
            return (Long) args[0];
        }
        return null;
    }

    private String safeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
