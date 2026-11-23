package com.solo.domain.resource.model;

/**
 * 资源种类：只读守卫需要的"这道写操作落在哪张表"。
 *
 * <p>与 {@code res_*} 四张表一一对应。刻意做成枚举而不是字符串：
 * 注解里写错一个字母会编译不过，而不是运行时静默漏校验——漏一层守卫的代价是
 * "下级平台的镜像被本地用户改掉了身份字段"。</p>
 */
public enum ResourceKind {

    ORG,
    GROUP,
    DEVICE,
    CHANNEL;

    /** 便于日志与错误文案（也是表名的后半段：res_ + table()) */
    public String table() {
        return name().toLowerCase();
    }
}
