package com.solo.aop;

import com.solo.domain.resource.model.ResourceKind;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只读收口第一层：给写接口声明"我要改哪种资源"（3.6.5）。
 *
 * <p>切面在方法执行前按 {@link #idParam()} 取到资源 id，回查 {@code source_type}，
 * 非 {@code NATIVE} 直接 403。它拦的是"前端点进来之后"——而前端本来就不该渲染入口，
 * 所以这条注解是防"绕过界面直接打接口"，不是防误操作。</p>
 *
 * <p><b>新建类接口不要加</b>：新建时资源还没有 id，写入的是本地数据；
 * 新建在外部资源"下面"的情况由领域层校验父节点（{@code OrgService.create} 等）。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResourceWrite {

    /** 目标资源种类 */
    ResourceKind value();

    /**
     * 方法参数里承载资源 id 的参数名。
     *
     * <p>走参数名而不是"第几个参数"：控制器签名调整顺序时不会静默指向另一个值。</p>
     */
    String idParam() default "id";

    /** 操作场景，写进 403 文案（"不能修改组织"） */
    String scene() default "";
}
