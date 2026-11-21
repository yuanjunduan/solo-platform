package com.solo.gb28181.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solo.gb28181.entity.GbDeviceChannel;
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

    /** 状态统计；platformId 非空时只统计该下级平台上报的目录 */
    @Select("""
            <script>
            SELECT parental, status, COUNT(*) AS count FROM gb_device_channel
            <where>
                <if test="platformId != null and platformId != ''">platform_id = #{platformId}</if>
            </where>
            GROUP BY parental, status
            </script>
            """)
    List<GbChannelStatusCountVO> selectStatusCount(@Param("platformId") String platformId);

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
