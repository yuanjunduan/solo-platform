package com.solo.domain.resource.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.solo.api.resource.dto.OrgSaveRequest;
import com.solo.common.ServiceException;
import com.solo.domain.resource.model.ResOrg;
import com.solo.domain.resource.service.impl.OrgServiceImpl;
import com.solo.domain.resource.support.ReadOnlyGuard;
import com.solo.infra.mapper.ResChannelMapper;
import com.solo.infra.mapper.ResDeviceMapper;
import com.solo.infra.mapper.ResOrgMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 组织服务：业务键唯一、路径由 id 推导、换父一次重写整棵子树。
 *
 * <p>这里全是 mock 单测，不连库：要验证的是"调了哪几条 SQL、参数对不对"，
 * 而不是 SQL 本身能不能跑（那要靠 DDL 脚本与联调）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgServiceImplTest {

    @Mock
    private ResOrgMapper orgMapper;
    @Mock
    private ResDeviceMapper deviceMapper;
    @Mock
    private ResChannelMapper channelMapper;
    @Spy
    private ReadOnlyGuard readOnlyGuard = new ReadOnlyGuard();
    @InjectMocks
    private OrgServiceImpl service;

    /**
     * 纯 Mockito 环境没有 MyBatis-Plus 的启动期建表元数据，
     * 而 update 走 LambdaUpdateWrapper.set(ResOrg::getName, ...) 需要这份 lambda 缓存，
     * 因此这里手工初始化一次，否则会抛 "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ResOrg.class);
    }

    @Test
    void createRootDerivesPathAndLevelFromId() {
        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode("ORG-1");
        req.setName("总部");

        ResOrg created = service.create(req);

        assertThat(created.getPath()).isEqualTo("/" + created.getId() + "/");
        assertThat(created.getLevel()).isEqualTo(1);
        assertThat(created.getParentId()).isNull();
        // 来源必须是 NATIVE：INBOUND 的来源只能由同步链路写入
        assertThat(created.getSourceType()).isEqualTo("NATIVE");
        assertThat(created.getSourcePlatformId()).isZero();
        verify(orgMapper).insert(created);
    }

    @Test
    void createChildAppendsParentPath() {
        ResOrg parent = org(1L, "/1/", 1);
        when(orgMapper.selectById(1L)).thenReturn(parent);

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode("ORG-2");
        req.setName("分公司");
        req.setParentId(1L);

        ResOrg created = service.create(req);

        assertThat(created.getPath()).isEqualTo("/1/" + created.getId() + "/");
        assertThat(created.getLevel()).isEqualTo(2);
        assertThat(created.getParentCode()).isEqualTo(parent.getCode());
    }

    @Test
    void createRejectsDuplicateCodeUnderSameSource() {
        when(orgMapper.selectByBizKey("NATIVE", 0L, "ORG-1")).thenReturn(org(5L, "/5/", 1));

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode("ORG-1");
        req.setName("总部");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("组织编码已存在");
        verify(orgMapper, never()).insert(any(ResOrg.class));
    }

    @Test
    void updateRejectsCodeChange() {
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "/9/", 1));

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode("ORG-NEW");
        req.setName("改名");

        assertThatThrownBy(() -> service.update(9L, req))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void updateRejectsMovingIntoOwnSubtree() {
        ResOrg self = org(9L, "/1/9/", 2);
        when(orgMapper.selectById(9L)).thenReturn(self);
        // 目标父节点落在自己的子树里 → 会成环
        when(orgMapper.selectById(10L)).thenReturn(org(10L, "/1/9/10/", 3));

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode(self.getCode());
        req.setName(self.getName());
        req.setParentId(10L);

        assertThatThrownBy(() -> service.update(9L, req))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自己的下级");
    }

    @Test
    void updateRejectsSelfAsParent() {
        ResOrg self = org(9L, "/9/", 1);
        when(orgMapper.selectById(9L)).thenReturn(self);

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode(self.getCode());
        req.setName(self.getName());
        req.setParentId(9L);

        assertThatThrownBy(() -> service.update(9L, req))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自己的上级");
    }

    @Test
    void moveRewritesOrgSubtreeThenShiftsLevelThenRefreshesResourcePaths() {
        ResOrg self = org(9L, "/1/5/9/", 3);
        self.setSourceType("NATIVE");
        self.setSourcePlatformId(0L);
        when(orgMapper.selectById(9L)).thenReturn(self);
        ResOrg newParent = org(2L, "/2/", 1);
        when(orgMapper.selectById(2L)).thenReturn(newParent);

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode(self.getCode());
        req.setName(self.getName());
        req.setParentId(2L);

        service.update(9L, req);

        String oldPrefix = "/1/5/9/";
        String newPrefix = "/2/9/";
        // 1) 先按旧前缀整棵换 path（含自身）
        verify(orgMapper).rewriteSubtreePath(
                eq("NATIVE"), eq(0L), eq(oldPrefix), eq(newPrefix), any());
        // 2) 再按新前缀平移层级：3 层 → 2 层，delta = -1
        verify(orgMapper).shiftSubtreeLevel(
                eq("NATIVE"), eq(0L), eq(newPrefix), eq(-1), any());
        // 3) 设备与通道的位置快照同步刷新（不按来源过滤，避免多来源漏刷）
        verify(deviceMapper).rewriteOrgPath(eq(oldPrefix), eq(newPrefix), any());
        verify(channelMapper).rewriteOrgPath(eq(oldPrefix), eq(newPrefix), any());
        verify(orgMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void updateWithoutMoveDoesNotTouchSubtree() {
        ResOrg self = org(9L, "/1/9/", 2);
        when(orgMapper.selectById(9L)).thenReturn(self);

        OrgSaveRequest req = new OrgSaveRequest();
        req.setCode(self.getCode());
        req.setName("新名字");
        req.setParentId(self.getParentId());

        service.update(9L, req);

        verify(orgMapper, never()).rewriteSubtreePath(any(), any(), any(), any(), any());
        verify(orgMapper, never()).shiftSubtreeLevel(any(), any(), any(), anyInt(), any());
        verify(deviceMapper, never()).rewriteOrgPath(any(), any(), any());
        verify(channelMapper, never()).rewriteOrgPath(any(), any(), any());
    }

    @Test
    void deleteRejectsWhenChildrenExist() {
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "/9/", 1));
        when(orgMapper.countByParentId(9L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("子组织");
        verify(orgMapper, never()).deleteById(9L);
    }

    @Test
    void deleteRejectsWhenDevicesStillAssigned() {
        when(orgMapper.selectById(9L)).thenReturn(org(9L, "/9/", 1));
        when(orgMapper.countByParentId(9L)).thenReturn(0L);
        when(deviceMapper.countByOrgId(9L)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("设备");
        verify(orgMapper, never()).deleteById(9L);
    }

    @Test
    void deleteRejectsInboundOrgWithForbidden() {
        ResOrg inbound = org(9L, "/9/", 1);
        inbound.setSourceType("INBOUND");
        when(orgMapper.selectById(9L)).thenReturn(inbound);

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ServiceException.class)
                .satisfies(e -> assertThat(((ServiceException) e).getCode()).isEqualTo(403));
        verify(orgMapper, never()).deleteById(9L);
    }

    private static ResOrg org(Long id, String path, int level) {
        ResOrg org = new ResOrg();
        org.setId(id);
        org.setCode("C" + id);
        org.setName("组织" + id);
        org.setPath(path);
        org.setLevel(level);
        org.setSourceType("NATIVE");
        org.setSourcePlatformId(0L);
        return org;
    }
}
