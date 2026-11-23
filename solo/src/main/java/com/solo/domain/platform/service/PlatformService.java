package com.solo.domain.platform.service;

import com.solo.api.platform.dto.PlatformListItem;
import com.solo.api.platform.dto.PlatformSaveRequest;
import com.solo.domain.platform.model.Platform;

import java.util.List;

/**
 * 平台接入（一个外部平台一条记录，能力位表达收/发方向）。
 *
 * <p>删除这里刻意做得保守：只要该平台还有入站资源，就拒绝删除并要求先"清空镜像"
 * （M2a 还没有清空入口，等于暂时不允许删有数据的平台）。理由是全删镜像等于
 * 绕过只读收口对资源做批量删除，必须走显式动作，不能藏在"删个平台"里。</p>
 */
public interface PlatformService {

    /**
     * @param keyword  名称/编码模糊匹配，可空
     * @param direction INBOUND（它给我）/ OUTBOUND（我给它）/ 空=全部
     */
    List<PlatformListItem> list(String keyword, String direction);

    Platform detail(Long id);

    Platform create(PlatformSaveRequest req);

    Platform update(Long id, PlatformSaveRequest req);

    /** 仅切状态（停用/启用）：停用后不可发起同步，也不接受入站目录 */
    void changeStatus(Long id, String status);

    /** @throws com.solo.common.ServiceException 还有入站资源 / 有活跃轮次时拒绝 */
    void delete(Long id);
}
