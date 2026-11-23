package com.solo.aop;

import com.solo.domain.resource.model.ResourceKind;
import com.solo.domain.resource.support.ReadOnlyGuard;
import com.solo.domain.resource.support.ResourceSourceLookup;
import com.solo.domain.resource.support.ResourceWriteArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 只读收口第一层：注解切面（3.6.5）。
 *
 * <p>顺序放在权限校验之后、业务方法之前：没权限的人不该先触发一次"这资源是哪来的"查询。
 * 用 {@code @Order(LOWEST_PRECEDENCE)} 让它贴着业务方法跑——真要被别的切面改写参数，
 * 越晚取到的才是最终值。</p>
 *
 * <p><b>取不到 id 时放行并告警</b>：注解漏配参数名属于开发期错误，告警让它浮出来；
 * 此刻直接抛异常会把正常请求打成 500，而第二、三层（服务守卫 + 拦截器）仍会兜住
 * 真正的越权写。</p>
 */
@Slf4j
@Aspect
@Component
@Order
@RequiredArgsConstructor
public class ResourceWriteAspect {

    private final ReadOnlyGuard readOnlyGuard;
    private final ResourceSourceLookup sourceLookup;

    @Around("@annotation(resourceWrite)")
    public Object around(ProceedingJoinPoint joinPoint, ResourceWrite resourceWrite) throws Throwable {
        ResourceKind kind = resourceWrite.value();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        Long id = ResourceWriteArgs.pickId(signature.getParameterNames(), joinPoint.getArgs(),
                resourceWrite.idParam());
        if (id == null) {
            log.warn("[资源只读守卫] @ResourceWrite 未取到 id，已放行 {}.{} kind={} idParam={}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), kind, resourceWrite.idParam());
            return joinPoint.proceed();
        }

        String sourceType = sourceLookup.sourceTypeOf(kind, id);
        if (sourceType == null) {
            // 资源不存在：交给业务方法报 404，守卫不抢这个错
            return joinPoint.proceed();
        }
        readOnlyGuard.requireWritable(sourceType, resourceWrite.scene());
        return joinPoint.proceed();
    }
}
