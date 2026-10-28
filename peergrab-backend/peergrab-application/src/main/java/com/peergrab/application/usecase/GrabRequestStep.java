package com.peergrab.application.usecase;

import com.peergrab.domain.grab.model.GrabRequest;
import com.peergrab.domain.grab.model.GrabRequestStatus;
import com.peergrab.domain.grab.ports.GrabRecordRepository;
import com.peergrab.domain.grab.ports.GrabRequestRepository;
import com.peergrab.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抢单请求的登记、终结与恢复裁决。
 *
 * <p>事务边界只包住数据库动作，不在这里访问 Redis；Redis 补偿由调用方在事务外执行。
 * 恢复裁决遵循一条硬规则：先用 grab_record + requestId 复核"业务是否真的提交过"，
 * 能证明提交就收敛为 SUCCEEDED，否则才标记 EXPIRED——绝不用"没查到记录"反推失败之外的东西。</p>
 */
@Service
public class GrabRequestStep {

    private final GrabRequestRepository requests;
    private final GrabRecordRepository records;

    public GrabRequestStep(GrabRequestRepository requests,
                           GrabRecordRepository records) {
        this.requests = requests;
        this.records = records;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean register(GrabRequest.NewRequest request) {
        return requests.create(request);
    }

    /**
     * 正常路径终结：只有仍持有租约的执行线程才能把请求从 PROCESSING 推进到 SUCCEEDED/REJECTED。
     * 如果租约已经过期，说明同一请求可能被恢复线程接管，此时抛异常让调用方走查询/恢复分支。
     */
    @Transactional(rollbackFor = Exception.class)
    public GrabRequest finish(GrabRequest request, ErrorCode code, int httpStatus, boolean grabbed) {
        GrabRequest current = requests.lockById(request.campusId(), request.id()).orElseThrow();
        if (current.status().terminal()) {
            if (grabbed && current.status() == GrabRequestStatus.EXPIRED && hasCommitted(current)) {
                // 极端长事务/长 GC：恢复线程先判了 EXPIRED，但业务随后确实提交。
                // 只有 grab_record 能证明提交，才允许 EXPIRED -> SUCCEEDED 单向升级。
                if (requests.upgradeExpiredToSucceeded(current.campusId(), current.id(),
                        ErrorCode.OK.name(), 200, resultJson(current, ErrorCode.OK, true))) {
                    return requests.findById(current.campusId(), current.id()).orElseThrow();
                }
            }
            return current;
        }
        GrabRequestStatus target = grabbed ? GrabRequestStatus.SUCCEEDED : GrabRequestStatus.REJECTED;
        boolean updated = requests.finish(current.campusId(), current.id(), current.reservationToken(),
                target, code.name(), httpStatus, resultJson(current, code, grabbed));
        if (!updated) {
            throw new IllegalStateException("抢单请求终结条件已失效 id=" + current.id());
        }
        return requests.findById(current.campusId(), current.id()).orElseThrow();
    }

    /**
     * 恢复路径终结：租约已过期的请求由 worker/查询线程接管。
     * 先查 grab_record 证明业务是否已经提交，再决定收敛为 SUCCEEDED 还是 EXPIRED。
     *
     * @return recoveredSuccess=true 表示"业务已提交但请求行未终结"，调用方需要补跑提交后副作用
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconcileResult reconcile(GrabRequest stale) {
        GrabRequest current = requests.lockById(stale.campusId(), stale.id()).orElseThrow();
        if (current.status().terminal()) {
            return new ReconcileResult(current, false);
        }
        var committed = records.findGrabbedByRequestId(current.campusId(), current.errandId(),
                current.requestId(), current.requestRound());
        if (committed.isPresent() && committed.get().runnerId() == current.runnerId()) {
            if (!requests.finishRecovered(current.campusId(), current.id(), GrabRequestStatus.SUCCEEDED,
                    ErrorCode.OK.name(), 200, resultJson(current, ErrorCode.OK, true))) {
                throw new IllegalStateException("请求恢复终结条件已失效 id=" + current.id());
            }
            return new ReconcileResult(reload(current), true);
        }
        if (!requests.finishRecovered(current.campusId(), current.id(), GrabRequestStatus.EXPIRED,
                ErrorCode.REQUEST_EXPIRED.name(), 409, resultJson(current, ErrorCode.REQUEST_EXPIRED, false))) {
            throw new IllegalStateException("请求恢复终结条件已失效 id=" + current.id());
        }
        return new ReconcileResult(reload(current), false);
    }

    private GrabRequest reload(GrabRequest request) {
        return requests.findById(request.campusId(), request.id()).orElseThrow();
    }

    /** 按 grab_record + requestId + round 判断业务是否已提交（供 v2 对未知结果做非终态处理）。 */
    public boolean hasCommitted(GrabRequest request) {
        var committed = records.findGrabbedByRequestId(request.campusId(), request.errandId(),
                request.requestId(), request.requestRound());
        return committed.isPresent() && committed.get().runnerId() == request.runnerId();
    }

    /** 固定枚举 + UUID 生成的结果快照；不拼接任何用户输入，保证持久结果可重放。 */
    public static String resultJson(GrabRequest request, ErrorCode code, boolean grabbed) {
        return String.format("{\"code\":\"%s\",\"grabbed\":%s,\"requestId\":\"%s\",\"appliedRound\":%d}",
                code.name(), grabbed, request.requestId(), request.requestRound());
    }

    public record ReconcileResult(GrabRequest request, boolean recoveredSuccess) {
    }
}
