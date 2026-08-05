package com.enterprise.kb.ai.tool;

import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 工具审批账本（设计文档 11.2.1 HITL 三段式，任务 3.4 复审要素①）
 *
 * <p>approvalId 走 Redis（弃草稿「approval:{employeeId}:{leaveType}」弱键形态）：
 * <ul>
 *   <li><b>TTL</b>——每审批单 {@code rag.tool.approval.ttl-minutes}（默认 10 分钟），
 *       过期未确认自动失效，approve 时续期同窗口</li>
 *   <li><b>一次性消费</b>——consume 校验通过后即删除键，重放无效</li>
 *   <li><b>绑定 tenant/user</b>——创建时记录发起者，approve/consume 均校验绑定，
 *       跨租户/跨用户拿到的 approvalId 不可用（防重放/防越权）</li>
 * </ul>
 *
 * <p><b>写操作 fail-closed</b>：Redis 故障时 create/consume 抛
 * {@code APPROVAL_STORE_UNAVAILABLE}——写工具据此拒绝执行（宁可不可用，
 * 不可绕过审批落写），与检索 fail-closed 租户隔离同哲学；读工具不经本服务，不受影响。
 *
 * <p>存储形态取 RMap&lt;String,String&gt;（字符串键值对任意 Redisson codec 均安全，
 * 规避 Java 对象序列化对 codec 的隐式依赖）。
 */
@Slf4j
@Service
public class ToolApprovalService {

    static final String KEY_PREFIX = "rag:tool-approval:";

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";

    private final RedissonClient redissonClient;
    private final long ttlMinutes;

    public ToolApprovalService(RedissonClient redissonClient,
                               @Value("${rag.tool.approval.ttl-minutes:10}") long ttlMinutes) {
        this.redissonClient = redissonClient;
        this.ttlMinutes = ttlMinutes;
    }

    /** 创建待审批单，返回 approvalId；Redis 故障抛 APPROVAL_STORE_UNAVAILABLE（写操作 fail-closed） */
    public String createPending(String tenantId, String userId, String toolName, String argsSummary) {
        String approvalId = UUID.randomUUID().toString();
        try {
            RMap<String, String> ledger = redissonClient.getMap(KEY_PREFIX + approvalId);
            ledger.putAll(Map.of(
                "tenantId", tenantId,
                "userId", userId,
                "toolName", toolName,
                "argsSummary", argsSummary,
                "status", STATUS_PENDING));
            ledger.expire(Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.error("审批单创建失败（Redis 故障），写操作 fail-closed 拒绝: {}", e.getMessage());
            throw new BusinessException("APPROVAL_STORE_UNAVAILABLE", "审批服务暂不可用，请稍后再试");
        }
        return approvalId;
    }

    /**
     * 用户确认审批：校验存在 + 绑定 tenant/user + 状态 PENDING → 置 APPROVED。
     * 任一不满足返回 false（不暴露失败细节，防探测）。
     */
    public boolean approve(String approvalId, String tenantId, String userId) {
        try {
            RMap<String, String> ledger = redissonClient.getMap(KEY_PREFIX + approvalId);
            if (ledger.isEmpty() || !boundTo(ledger, tenantId, userId)
                || !STATUS_PENDING.equals(ledger.get("status"))) {
                return false;
            }
            ledger.put("status", STATUS_APPROVED);
            ledger.expire(Duration.ofMinutes(ttlMinutes));
            return true;
        } catch (Exception e) {
            log.error("审批确认失败（Redis 故障）: {}", e.getMessage());
            throw new BusinessException("APPROVAL_STORE_UNAVAILABLE", "审批服务暂不可用，请稍后再试");
        }
    }

    /**
     * 工具执行前校验并**一次性消费**：状态 APPROVED + 绑定校验 → 删除键返回 true；
     * 重复消费/过期/未确认/越权均 false。
     */
    public boolean consume(String approvalId, String tenantId, String userId) {
        try {
            RMap<String, String> ledger = redissonClient.getMap(KEY_PREFIX + approvalId);
            if (ledger.isEmpty() || !boundTo(ledger, tenantId, userId)
                || !STATUS_APPROVED.equals(ledger.get("status"))) {
                return false;
            }
            ledger.delete();
            return true;
        } catch (Exception e) {
            log.error("审批消费失败（Redis 故障），写操作 fail-closed 拒绝: {}", e.getMessage());
            throw new BusinessException("APPROVAL_STORE_UNAVAILABLE", "审批服务暂不可用，请稍后再试");
        }
    }

    private static boolean boundTo(RMap<String, String> ledger, String tenantId, String userId) {
        return Objects.equals(ledger.get("tenantId"), tenantId)
            && Objects.equals(ledger.get("userId"), userId);
    }
}
