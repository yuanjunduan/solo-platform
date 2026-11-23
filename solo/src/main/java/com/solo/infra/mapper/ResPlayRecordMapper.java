package com.solo.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solo.domain.resource.model.ResPlayRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 播放/回放请求记录（M6）。查询走 LambdaQueryWrapper。
 *
 * <p>M7 增加保留期分批删除：这是一张按观看行为线性增长的表，
 * 不回收就会盖过资源表（5.2.1「不清理会长成第二张大表」）。</p>
 */
@Mapper
public interface ResPlayRecordMapper extends BaseMapper<ResPlayRecord> {

    @Delete("""
            DELETE FROM res_play_record WHERE id IN (
                SELECT id FROM (
                    SELECT id FROM res_play_record WHERE begin_time < #{before} LIMIT #{limit}
                ) t
            )
            """)
    int deleteBeforeBatch(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
