package com.solo.domain.resource.support;

import com.solo.common.ServiceException;
import com.solo.domain.resource.model.SourceType;
import org.springframework.stereotype.Component;

/**
 * 只读守卫（M1 骨架版，3.6.5 四层收口的第一层）。
 *
 * <p><b>规则只有一条</b>：只有 {@code NATIVE} 资源允许用户写；{@code INBOUND} 是外部平台的目录镜像，
 * 任何管理 API 都不得新增、修改、删除（需求硬约束 C2）。</p>
 *
 * <p>之所以先建骨架再接入站数据：如果等 M2 有了入站资源才加校验，M1 写的那批 CRUD
 * 会各写一遍判断，后面要挨个改；现在统一走这一个方法，M2 只需要保证"所有写入点都调它"。</p>
 *
 * <p>四层收口的分工（本类只负责第二层）：注解层拦前端可见入口、本层拦服务方法、
 * 拦截器层兜底 API、DB 权限做最后一道。任一层失效，其余三层仍然拒。</p>
 */
@Component
public class ReadOnlyGuard {

    private static final int FORBIDDEN = 403;

    /**
     * 要求资源可写。
     *
     * @param sourceType 资源来源类型
     * @param scene      操作场景（写进错误文案，让用户知道"哪一步被拦了"）
     * @throws ServiceException 非 NATIVE 时抛 403
     */
    public void requireWritable(String sourceType, String scene) {
        if (SourceType.isNative(sourceType)) {
            return;
        }
        throw new ServiceException(FORBIDDEN,
                "该资源来自外部平台，只能由同步自动更新，不能" + (scene == null ? "修改" : scene));
    }

    /** 供展示层判断要不要渲染编辑入口（返回 false 时按钮应隐藏，而不是点了再报错） */
    public boolean isWritable(String sourceType) {
        return SourceType.isNative(sourceType);
    }
}
