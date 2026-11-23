package com.solo.domain.share.support;

/**
 * 暴露码生成与解析（C16 / 3.9.3）。
 *
 * <p>规则：{@code 本平台SIP域(10位) + 原类型段(3位) + 序号(7位)}，共 20 位。
 * 序号<b>按平台内单调分配、删除不复用</b>——墓碑保留就是为了这个。</p>
 *
 * <p>三处纪律（写在这里，别在服务里另写一套）：</p>
 * <ol>
 *   <li>冲突时<b>只动序号段</b>重新分配；禁止在 20 位码尾部追加字符（会破坏校验位，上级拒收）。</li>
 *   <li>编码一经分配<b>不随资源改名/移动变化</b>。</li>
 *   <li>上级拒绝同码节点时<b>不自动改码</b>（会让上级产生孤儿节点），记错误转人工。</li>
 * </ol>
 */
public final class ExposedCode {

    /** 设备 */
    public static final String SEG_DEVICE = "200";
    /** 通道 */
    public static final String SEG_CHANNEL = "131";
    /** 业务分组 */
    public static final String SEG_BUSINESS_GROUP = "215";
    /** 虚拟组织 */
    public static final String SEG_VIRTUAL_ORG = "216";

    public static final int DOMAIN_LEN = 10;
    public static final int SEG_LEN = 3;
    public static final long SEQ_MIN = 1;
    public static final long SEQ_MAX = 9_999_999L;

    private ExposedCode() {
    }

    /**
     * 拼一个 20 位暴露码。
     *
     * @param localDomain 本平台在该连接中使用的 SIP 域（10 位数字）
     * @param typeSeg     类型段（3 位）
     * @param seq         序号（1 ~ 9999999）
     */
    public static String of(String localDomain, String typeSeg, long seq) {
        requireDomain(localDomain);
        if (!isValidSeg(typeSeg)) {
            throw new IllegalArgumentException("类型段必须是 3 位数字：" + typeSeg);
        }
        if (seq < SEQ_MIN || seq > SEQ_MAX) {
            throw new IllegalArgumentException("序号超出 7 位范围：" + seq);
        }
        return localDomain + typeSeg + String.format("%07d", seq);
    }

    /** 资源类型 → 类型段。ORG 只在规则行出现，不作为暴露节点。 */
    public static String segOf(String refType) {
        if (refType == null) {
            throw new IllegalArgumentException("资源类型不能为空");
        }
        return switch (refType) {
            case "CHANNEL" -> SEG_CHANNEL;
            case "DEVICE" -> SEG_DEVICE;
            case "GROUP" -> SEG_VIRTUAL_ORG;
            default -> throw new IllegalArgumentException("不支持暴露的资源类型：" + refType);
        };
    }

    public static String domainOf(String code) {
        if (code == null || code.length() < DOMAIN_LEN) {
            return null;
        }
        return code.substring(0, DOMAIN_LEN);
    }

    public static String segOfCode(String code) {
        if (code == null || code.length() < DOMAIN_LEN + SEG_LEN) {
            return null;
        }
        return code.substring(DOMAIN_LEN, DOMAIN_LEN + SEG_LEN);
    }

    /** 序号段；解析不出或为 0 返回 -1（0 不是合法序号） */
    public static long seqOf(String code) {
        if (code == null || code.length() != DOMAIN_LEN + SEG_LEN + 7) {
            return -1;
        }
        try {
            long seq = Long.parseLong(code.substring(DOMAIN_LEN + SEG_LEN));
            return seq >= SEQ_MIN ? seq : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 该码是否属于本平台域（四道防环的第 4 道：自身域） */
    public static boolean belongsTo(String code, String localDomain) {
        return code != null && localDomain != null
                && code.length() >= DOMAIN_LEN
                && code.startsWith(localDomain);
    }

    public static boolean isValidSeg(String typeSeg) {
        if (typeSeg == null || typeSeg.length() != SEG_LEN) {
            return false;
        }
        for (int i = 0; i < SEG_LEN; i++) {
            if (!Character.isDigit(typeSeg.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 域必须是 10 位数字。
     * <p>不校验位（末位校验码）是故意的：本项目只保证"在本平台域内"，校验位由上级的协议栈判断；
     * 强行自己算容易和各厂家的实现差异打架。</p>
     */
    public static void requireDomain(String localDomain) {
        if (localDomain == null || localDomain.length() != DOMAIN_LEN) {
            throw new IllegalArgumentException("SIP 域必须是 10 位：" + localDomain);
        }
        for (int i = 0; i < DOMAIN_LEN; i++) {
            if (!Character.isDigit(localDomain.charAt(i))) {
                throw new IllegalArgumentException("SIP 域必须是 10 位数字：" + localDomain);
            }
        }
    }
}
