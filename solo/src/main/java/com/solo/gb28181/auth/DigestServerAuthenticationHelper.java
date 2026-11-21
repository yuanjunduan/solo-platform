package com.solo.gb28181.auth;

import com.solo.gb28181.constant.GbRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sip.address.URI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * REGISTER 服务端 Digest 鉴权。
 *
 * <p>兼容 qop=auth 与不带 qop 的传统设备。nonce 必须由平台生成并写入 Redis，
 * 校验时同时核对设备 ID、realm 和有效期，避免客户端自行编造 nonce。</p>
 *
 * <p>HA1 = MD5(username:realm:password)<br>
 * HA2 = MD5(method:uri)<br>
 * qop=auth：response = MD5(HA1:nonce:nc:cnonce:qop:HA2)<br>
 * 无 qop：response = MD5(HA1:nonce:HA2)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestServerAuthenticationHelper {

    private static final String ALGORITHM = "MD5";
    private static final String SCHEME = "Digest";
    private static final String QOP_AUTH = "auth";
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);
    // 同 deviceId 的 401 挑战最小间隔：失败风暴时把回包频率压到 0.2 次/秒
    private static final Duration CHALLENGE_INTERVAL = Duration.ofSeconds(5);
    // 连续鉴权失败达到该次数后进入封禁，窗口内静默丢弃
    private static final long MAX_AUTH_FAILURES = 5;
    private static final Duration AUTH_FAIL_WINDOW = Duration.ofMinutes(1);
    private static final Duration AUTH_BAN_TTL = Duration.ofMinutes(1);
    // 同一设备鉴权失败日志的聚合窗口
    private static final Duration FAILURE_LOG_WINDOW = Duration.ofMinutes(1);
    private static final int NONCE_BYTES = 32;
    private static final int NONCE_LENGTH = NONCE_BYTES * 2;
    private static final int DIGEST_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    /**
     * 向响应写入 401 挑战头。
     *
     * <p>同 deviceId+realm 在 {@link #NONCE_TTL} 内复用同一个 nonce：鉴权失败风暴时
     * 对端每秒重发 REGISTER，若每次都生成新 nonce 就会每秒新建一个 Redis 键，
     * 5 分钟滚动几万个键；复用后同一设备只占一个键。</p>
     *
     * <p>Redis 读写失败时直接抛出异常，调用方不得继续接受注册。</p>
     */
    public void appendChallenge(HeaderFactory headerFactory, Response response,
                                String deviceId, String realm) throws ParseException {
        String nonce;
        String currentKey = GbRedisKeys.REGISTER_NONCE_CURRENT_PREFIX + deviceId + ":" + realm;
        String cached = redisTemplate.opsForValue().get(currentKey);
        if (cached != null && isHex(cached, NONCE_LENGTH)) {
            nonce = cached;
            // 绑定键可能已过期/被清理，补写但不刷新已有值
            redisTemplate.opsForValue().setIfAbsent(
                    GbRedisKeys.REGISTER_NONCE_PREFIX + nonce,
                    nonceBinding(deviceId, realm),
                    NONCE_TTL);
        } else {
            nonce = generateNonce();
            redisTemplate.opsForValue().set(
                    GbRedisKeys.REGISTER_NONCE_PREFIX + nonce,
                    nonceBinding(deviceId, realm),
                    NONCE_TTL);
            redisTemplate.opsForValue().set(currentKey, nonce, NONCE_TTL);
        }

        WWWAuthenticateHeader auth = headerFactory.createWWWAuthenticateHeader(SCHEME);
        auth.setParameter("realm", realm);
        auth.setParameter("qop", QOP_AUTH);
        auth.setParameter("nonce", nonce);
        auth.setParameter("algorithm", ALGORITHM);
        response.setHeader(auth);
    }

    /**
     * 401 挑战限流：同 deviceId 每 {@link #CHALLENGE_INTERVAL} 最多回一次 401。
     *
     * <p>正常的“首包 401 → 带认证重发 → 200”流程不受影响：第二包鉴权成功就不会再回 401。
     * 只有对端持续鉴权失败时才命中限流，多余请求静默丢弃（不回包也不写日志），
     * 避免 401 与失败重发形成正反馈风暴把 SIP 线程池和 Redis 写满。</p>
     *
     * @return true 表示本次可以回 401；false 表示应静默丢弃
     */
    public boolean tryAcquireChallengeSlot(String deviceId) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    GbRedisKeys.REGISTER_CHALLENGE_LIMIT_PREFIX + deviceId, "1", CHALLENGE_INTERVAL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 异常时不阻断正常设备：后续 appendChallenge 会因写失败抛异常
            return true;
        }
    }

    /**
     * 是否处于鉴权失败封禁期。封禁期内所有 REGISTER 静默丢弃（不写库、不回包、不写日志）。
     */
    public boolean isAuthBanned(String deviceId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(GbRedisKeys.REGISTER_AUTH_BAN_PREFIX + deviceId));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 记一次鉴权失败；连续失败达到阈值后进入封禁，封禁期 1 分钟，靠 TTL 自动解封。
     */
    public void recordAuthFailure(String deviceId) {
        try {
            String failKey = GbRedisKeys.REGISTER_AUTH_FAIL_PREFIX + deviceId;
            Long count = redisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(failKey, AUTH_FAIL_WINDOW);
            }
            if (count != null && count >= MAX_AUTH_FAILURES) {
                redisTemplate.opsForValue().set(
                        GbRedisKeys.REGISTER_AUTH_BAN_PREFIX + deviceId, String.valueOf(count), AUTH_BAN_TTL);
                redisTemplate.delete(failKey);
            }
        } catch (Exception e) {
            log.warn("[注册鉴权] 记录失败次数异常 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    /** 鉴权通过后清空失败计数与封禁标记 */
    public void clearAuthFailure(String deviceId) {
        try {
            redisTemplate.delete(GbRedisKeys.REGISTER_AUTH_FAIL_PREFIX + deviceId);
            redisTemplate.delete(GbRedisKeys.REGISTER_AUTH_BAN_PREFIX + deviceId);
        } catch (Exception e) {
            log.warn("[注册鉴权] 清理失败计数异常 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    /**
     * 鉴权失败日志聚合：同一设备 1 分钟内只放行一条 WARN，避免风暴刷盘。
     *
     * @return true 表示本次应打印失败日志
     */
    public boolean shouldLogFailure(String deviceId) {
        try {
            String logKey = GbRedisKeys.REGISTER_AUTH_LOG_PREFIX + deviceId;
            Long count = redisTemplate.opsForValue().increment(logKey);
            if (count != null && count == 1L) {
                redisTemplate.expire(logKey, FAILURE_LOG_WINDOW);
                return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 鉴权失败的诊断信息（不包含密码与摘要），用于定位 username/realm/URI 不匹配。
     */
    public String describeAuthorization(Request request) {
        AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
        if (auth == null) {
            return "无 Authorization 头";
        }
        return "username=" + auth.getUsername()
                + " realm=" + auth.getRealm()
                + " digestUri=" + auth.getURI()
                + " requestUri=" + request.getRequestURI();
    }

    /**
     * 校验 REGISTER 的 Authorization 头。任何字段缺失、nonce 未签发或已过期、
     * realm/URI 不匹配、摘要错误都返回 false。
     */
    public boolean authenticate(Request request, String deviceId, String expectedRealm, String password) {
        AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
        if (auth == null || deviceId == null || expectedRealm == null || password == null) {
            return false;
        }

        String username = auth.getUsername();
        String realm = auth.getRealm();
        String nonce = auth.getNonce();
        URI digestUri = auth.getURI();
        URI requestUri = request.getRequestURI();
        // 不再强制 username == deviceId：海康等平台把「鉴权登录名」单独配置（当前为 solo@1024），
        // 与 From 头里的平台编码（deviceId）本来就可以不同。摘要仍按 username 计算，
        // nonce 仍绑定 deviceId 签发，安全性由“平台签发的 nonce + 全局注册密码”保证。
        if (username == null || username.isBlank() || !expectedRealm.equals(realm)
                || !isHex(nonce, NONCE_LENGTH) || digestUri == null || requestUri == null
                || !requestUri.toString().equals(digestUri.toString())) {
            return false;
        }

        String algorithm = auth.getAlgorithm();
        if (algorithm != null && !algorithm.isBlank() && !ALGORITHM.equalsIgnoreCase(algorithm)) {
            return false;
        }

        String issuedBinding = redisTemplate.opsForValue().get(GbRedisKeys.REGISTER_NONCE_PREFIX + nonce);
        if (!nonceBinding(deviceId, expectedRealm).equals(issuedBinding)) {
            return false;
        }

        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(request.getMethod().toUpperCase(Locale.ROOT) + ":" + digestUri);
        StringBuilder kd = new StringBuilder(ha1).append(':').append(nonce);
        String qop = auth.getQop();
        if (qop != null && !qop.isBlank()) {
            if (!QOP_AUTH.equalsIgnoreCase(qop)) {
                return false;
            }
            String nc = auth.getParameter("nc");
            String cnonce = auth.getCNonce();
            if (!isNonceCount(nc) || cnonce == null || cnonce.isBlank()) {
                return false;
            }
            // 必须使用设备报文中的原始 nc，不能用整数重新格式化，否则大小写变化会导致摘要不同。
            kd.append(':').append(nc)
                    .append(':').append(cnonce)
                    .append(':').append(qop);
        }
        kd.append(':').append(ha2);

        String expected = md5Hex(kd.toString());
        String actual = auth.getResponse();
        if (!isHex(actual, DIGEST_LENGTH)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static String nonceBinding(String deviceId, String realm) {
        return deviceId + "\n" + realm;
    }

    private static String generateNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static boolean isNonceCount(String nc) {
        return nc != null && nc.length() == 8 && nc.chars().allMatch(DigestServerAuthenticationHelper::isHexDigit);
    }

    private static boolean isHex(String value, int expectedLength) {
        return value != null && value.length() == expectedLength
                && value.chars().allMatch(DigestServerAuthenticationHelper::isHexDigit);
    }

    private static boolean isHexDigit(int c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static String md5Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 MD5", e);
        }
    }
}
