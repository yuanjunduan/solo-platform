package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.platform.model.PlatformCredential;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台凭证持久化。密文读写只允许经 {@code CredentialService}，不对外暴露实体。
 */
@Mapper
public interface PlatformCredentialMapper extends BaseMapper<PlatformCredential> {
}
