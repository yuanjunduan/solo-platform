package com.solo.domain.resource.model;

/**
 * 资源来源类型（架构文档 3.6.2：来源用字段表达，不用枚举表）。
 *
 * <p>只有 {@link #NATIVE} 可被用户增删改；{@link #INBOUND} 是外部平台的目录镜像，
 * 一律由同步链路写入，任何管理 API 都不得修改（C2 / 3.6.5 四层收口）。</p>
 */
public final class SourceType {

    /** 本地自建资源：{@code source_platform_id = 0} */
    public static final String NATIVE = "NATIVE";

    /** 外部平台同步进来的镜像资源：{@code source_platform_id = 直接来源平台 id} */
    public static final String INBOUND = "INBOUND";

    private SourceType() {
    }

    public static boolean isNative(String sourceType) {
        return NATIVE.equals(sourceType);
    }
}
