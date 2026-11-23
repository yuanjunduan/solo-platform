package com.solo.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.solo.aop.PermissionCache;
import com.solo.common.ServiceException;
import com.solo.common.utils.TreeUtils;
import com.solo.system.constant.SystemConstants;
import com.solo.system.entity.SysPermission;
import com.solo.system.entity.SysRolePermission;
import com.solo.system.mapper.SysPermissionMapper;
import com.solo.system.mapper.SysRolePermissionMapper;
import com.solo.system.request.PermissionRequest;
import com.solo.system.service.SysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission> implements SysPermissionService {

    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final PermissionCache permissionCache;

    @Override
    public List<SysPermission> getTree() {
        return list(new LambdaQueryWrapper<SysPermission>()
                .orderByAsc(SysPermission::getSort));
    }

    @Override
    @Transactional
    public void createPermission(PermissionRequest request) {
        SysPermission existing = getOne(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getPermissionCode, request.getPermissionCode()));
        if (existing != null) {
            throw new ServiceException("权限编码已存在");
        }
        // 已逻辑删除的同编码记录仍占着 uk_permission_code（唯一键不含 deleted），先清墓碑再插入，
        // 否则复用编码会撞唯一键、被兜底成 500「系统异常，请联系管理员」。
        baseMapper.purgeDeletedByPermissionCode(request.getPermissionCode());
        SysPermission permission = new SysPermission();
        permission.setPermissionName(request.getPermissionName());
        permission.setPermissionCode(request.getPermissionCode());
        permission.setUrl(request.getUrl());
        permission.setType(request.getType());
        permission.setParentId(request.getParentId() != null ? request.getParentId() : SystemConstants.DEFAULT_PARENT_ID);
        permission.setSort(request.getSort() != null ? request.getSort() : SystemConstants.DEFAULT_SORT);
        permission.setIcon(request.getIcon());
        permission.setStatus(request.getStatus() != null ? request.getStatus() : SystemConstants.STATUS_ENABLED);
        save(permission);
    }

    @Override
    @Transactional
    public void updatePermission(Long id, PermissionRequest request) {
        SysPermission permission = getById(id);
        if (permission == null) {
            throw new ServiceException(SystemConstants.HTTP_NOT_FOUND, "权限不存在");
        }
        if (request.getPermissionName() != null) permission.setPermissionName(request.getPermissionName());
        if (request.getPermissionCode() != null) {
            // 改名复用某个已删除的编码，同样会撞 uk_permission_code，先清墓碑
            baseMapper.purgeDeletedByPermissionCode(request.getPermissionCode());
            permission.setPermissionCode(request.getPermissionCode());
        }
        if (request.getUrl() != null) permission.setUrl(request.getUrl());
        if (request.getType() != null) permission.setType(request.getType());
        if (request.getParentId() != null) permission.setParentId(request.getParentId());
        if (request.getSort() != null) permission.setSort(request.getSort());
        if (request.getIcon() != null) permission.setIcon(request.getIcon());
        if (request.getStatus() != null) permission.setStatus(request.getStatus());
        updateById(permission);
    }

    @Override
    @Transactional
    public void deletePermission(Long id) {
        // 与 deleteRole 对齐：先清角色授权关联再删权限，避免 sys_role_permission 留下指向已删权限的孤儿行
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getPermissionId, id));
        removeById(id);
        // 权限集合变了，所有用户的鉴权结果都受影响；逐用户失效成本太高，直接全清
        evictAllAfterCommit();
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        // 空集合会让条件构造器拼出 IN () 这类非法 SQL，必须先挡住
        if (ids == null || ids.isEmpty()) return;
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                .in(SysRolePermission::getPermissionId, ids));
        removeByIds(ids);
        evictAllAfterCommit();
    }

    /**
     * 权限缓存全量失效，但挂到事务提交之后执行。
     *
     * <p>提交前清缓存会留一个窗口：并发请求命中鉴权 → 回源查库（看不到本事务未提交的改动）
     * → 把仍含被删权限的旧列表写回缓存；而 PermissionCache 没有 TTL，这条脏结果会一直留着。
     * 事务回滚时不清缓存，原有授权保持原样。</p>
     */
    private void evictAllAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        permissionCache.evictAll();
                    }
                }
            });
            return;
        }
        // 没有事务上下文（被非事务方法直接调用）时立即失效
        permissionCache.evictAll();
    }

    @Override
    public List<SysPermission> buildTree(List<SysPermission> allPermissions, Long parentId) {
        return TreeUtils.build(
                allPermissions,
                SysPermission::getParentId,
                SysPermission::getId,
                p -> p.getParentId() != null && p.getParentId().equals(parentId),
                SysPermission::setChildren
        );
    }
}
