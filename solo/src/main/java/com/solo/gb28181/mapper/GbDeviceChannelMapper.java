package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.gb28181.entity.GbDeviceChannel;
import com.solo.gb28181.response.GbCatalogSourceVO;
import com.solo.gb28181.response.GbChannelStatusCountVO;
import com.solo.gb28181.response.GbChannelTreeNodeVO;
import com.solo.gb28181.response.GbDeviceChannelResponse;
import com.solo.gb28181.response.GbTreeCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Mapper
public interface GbDeviceChannelMapper extends BaseMapper<GbDeviceChannel> {

    // 先合并批内重复业务键，再走 XML 里的按库方言 upsert
    default int batchUpsert(List<GbDeviceChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        return batchUpsertRaw(mergeDuplicates(channels));
    }

    int batchUpsertRaw(@Param("list") List<GbDeviceChannel> channels);

    /** 清空该设备的组织路径（重建第一步） */
    int resetTreePath(@Param("deviceId") String deviceId);

    /** 根分组（无父/挂设备节点）落 tree_path = /自身码 */
    int initRootTreePath(@Param("deviceId") String deviceId);

    /** 传播一轮：已算出路径的分组把路径带给直接下级；返回影响行数，0 表示收敛 */
    int propagateTreePath(@Param("deviceId") String deviceId);

    /** 兜底：父节点缺失（孤儿）或超深未算出路径的行挂回设备根；返回影响行数 */
    int rescueOrphanTreePath(@Param("deviceId") String deviceId);

    /** 已建路径的行数：为 0 说明是旧数据，首次访问树接口时补建 */
    int countTreePath(@Param("deviceId") String deviceId);

    /** 按父码取直接下级（分组 + 通道） */
    List<GbChannelTreeNodeVO> selectChildren(@Param("deviceId") String deviceId,
                                             @Param("parentIds") List<String> parentIds);

    /** 下级各节点的子树通道数（按 tree_path 首段汇总，一次扫描） */
    List<GbTreeCountVO> selectSubtreeChannelCount(@Param("deviceId") String deviceId,
                                                  @Param("prefix") String prefix);

    /** 指定国标码的直接下级数量（决定展开箭头） */
    List<GbTreeCountVO> selectChildCountByParent(@Param("deviceId") String deviceId,
                                                 @Param("parentIds") List<String> parentIds);

    /** 子树通道分页：includeSubtree=true 走 tree_path 前缀，否则只取 parent_id 直接挂载 */
    Page<GbDeviceChannelResponse> selectSubtreePage(Page<GbDeviceChannelResponse> page,
                                                    @Param("deviceId") String deviceId,
                                                    @Param("includeSubtree") boolean includeSubtree,
                                                    @Param("prefix") String prefix,
                                                    @Param("parentId") String parentId,
                                                    @Param("keyword") String keyword,
                                                    @Param("status") String status);

    /**
     * 状态统计；deviceId / platformId 非空时只统计该来源端点的目录（二者当前同值，优先用语义正确的 deviceId）。
     *
     * <p>domain 非空时只统计该资源域（域只落在 gb_device，通道通过 device_id 继承）；
     * orgIds 非空时按"有效组织"（通道自身 org_id 优先，否则继承设备）统计，并锁定本地域；
     * 集合为空则恒假；unassigned=true 表示只统计未归属。三个组织参数都不传时与旧行为一致。</p>
     */
    @Select("""
            <script>
            SELECT parental, status, COUNT(*) AS count FROM gb_device_channel
            <where>
                <if test="deviceId != null and deviceId != ''">device_id = #{deviceId}</if>
                <if test="platformId != null and platformId != ''">platform_id = #{platformId}</if>
                <if test="domain != null and domain != ''">
                    AND device_id IN (SELECT d.device_id FROM gb_device d WHERE d.domain = #{domain})
                </if>
                <if test="orgIds != null or unassigned">
                    AND device_id IN (SELECT d.device_id FROM gb_device d WHERE d.domain = 'LOCAL')
                </if>
                <if test="unassigned">
                    AND COALESCE(org_id, (SELECT d.org_id FROM gb_device d
                        WHERE d.device_id = gb_device_channel.device_id)) IS NULL
                </if>
                <if test="orgIds != null">
                    <choose>
                        <when test="orgIds.isEmpty()">AND 1 = 0</when>
                        <otherwise>
                            AND COALESCE(org_id, (SELECT d.org_id FROM gb_device d
                                WHERE d.device_id = gb_device_channel.device_id)) IN
                            <foreach collection="orgIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                        </otherwise>
                    </choose>
                </if>
                <if test="pathPrefix != null and pathPrefix != ''">
                    AND tree_path LIKE CONCAT(#{pathPrefix}, '/%')
                </if>
            </where>
            GROUP BY parental, status
            </script>
            """)
    List<GbChannelStatusCountVO> selectStatusCount(@Param("deviceId") String deviceId,
                                                   @Param("platformId") String platformId,
                                                   @Param("domain") String domain,
                                                   @Param("orgIds") List<Long> orgIds,
                                                   @Param("unassigned") boolean unassigned,
                                                   @Param("pathPrefix") String pathPrefix);

    /**
     * 目录来源聚合：来源维度直接取自目录数据自身（group by device_id），
     * 不依赖任何登记表；端点名称/在线态 left join gb_device 回填。
     */
    @Select("""
            SELECT c.device_id AS deviceId,
                   MAX(COALESCE(NULLIF(d.name, ''), NULLIF(d.report_name, ''), '')) AS name,
                   MAX(CASE WHEN d.domain IS NULL THEN 'SUBORDINATE' ELSE d.domain END) AS domain,
                   MAX(d.status) AS onlineStatus,
                   MAX(d.last_catalog_sync_time) AS lastCatalogSyncTime,
                   COUNT(*) AS itemCount,
                   SUM(CASE WHEN COALESCE(c.parental, 0) = 1 THEN 1 ELSE 0 END) AS groupCount,
                   SUM(CASE WHEN COALESCE(c.parental, 0) = 1 THEN 0 ELSE 1 END) AS channelCount,
                   SUM(CASE WHEN COALESCE(c.parental, 0) = 1 THEN 0 WHEN c.status = 'ON' THEN 1 ELSE 0 END) AS onlineChannelCount,
                   SUM(CASE WHEN COALESCE(c.parental, 0) = 1 THEN 0 WHEN c.status = 'OFF' THEN 1 ELSE 0 END) AS offlineChannelCount
            FROM gb_device_channel c
            LEFT JOIN gb_device d ON d.device_id = c.device_id
            GROUP BY c.device_id
            ORDER BY channelCount DESC, c.device_id
            """)
    List<GbCatalogSourceVO> selectCatalogSources();

    // 合并语义对齐 MySQL：后出现的非 null 上报值覆盖先出现的，null 不覆盖
    private static List<GbDeviceChannel> mergeDuplicates(List<GbDeviceChannel> channels) {
        Map<String, GbDeviceChannel> merged = new LinkedHashMap<>(channels.size());
        int invalid = 0;
        for (GbDeviceChannel ch : channels) {
            // channel_id 为空属于脏数据，不参与合并，交给库里的非空约束报错
            String key = ch.getChannelId() == null
                    ? "invalid#" + invalid++
                    : ch.getDeviceId() + '\u0000' + ch.getChannelId();
            GbDeviceChannel exist = merged.get(key);
            if (exist == null) {
                merged.put(key, ch);
            } else {
                mergeReportedFields(exist, ch);
            }
        }
        return merged.size() == channels.size() ? channels : new ArrayList<>(merged.values());
    }

    // 只合并冲突时会被更新的上报字段；id / parental / parent_id / source / create_time 保留首行
    private static void mergeReportedFields(GbDeviceChannel target, GbDeviceChannel later) {
        if (later.getReportName() != null) target.setReportName(later.getReportName());
        if (later.getName() != null) target.setName(later.getName());
        if (later.getManufacturer() != null) target.setManufacturer(later.getManufacturer());
        if (later.getModel() != null) target.setModel(later.getModel());
        if (later.getOwner() != null) target.setOwner(later.getOwner());
        if (later.getCivilCode() != null) target.setCivilCode(later.getCivilCode());
        if (later.getBlock() != null) target.setBlock(later.getBlock());
        if (later.getAddress() != null) target.setAddress(later.getAddress());
        if (later.getSafetyWay() != null) target.setSafetyWay(later.getSafetyWay());
        if (later.getRegisterWay() != null) target.setRegisterWay(later.getRegisterWay());
        if (later.getCertNum() != null) target.setCertNum(later.getCertNum());
        if (later.getCertifiable() != null) target.setCertifiable(later.getCertifiable());
        if (later.getSecrecy() != null) target.setSecrecy(later.getSecrecy());
        if (later.getIpAddress() != null) target.setIpAddress(later.getIpAddress());
        if (later.getPort() != null) target.setPort(later.getPort());
        if (later.getPassword() != null) target.setPassword(later.getPassword());
        if (later.getStatus() != null) target.setStatus(later.getStatus());
        if (later.getLongitude() != null) target.setLongitude(later.getLongitude());
        if (later.getLatitude() != null) target.setLatitude(later.getLatitude());
        if (later.getPtzType() != null) target.setPtzType(later.getPtzType());
    }
}
