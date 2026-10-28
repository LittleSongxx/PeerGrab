package com.peergrab.domain.grab.ports;

import com.peergrab.domain.grab.model.GrabRequest;
import com.peergrab.domain.grab.model.GrabRequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 抢单请求仓储。
 *
 * <p>所有查询都带 campus_id：当前虽然是单库，但分片键先贯通，
 * 后续真正接分片时不需要再改一轮 SQL 语义。</p>
 */
public interface GrabRequestRepository {

    /** 依赖唯一键 (campus_id, runner_id, operation, request_id) 保证同一请求只登记一次。 */
    boolean create(GrabRequest.NewRequest request);

    Optional<GrabRequest> find(long campusId, long runnerId, String requestId);

    Optional<GrabRequest> findById(long campusId, long id);

    /** 调用方必须已在事务内，并在拿到行锁后再读数据库时间。 */
    Optional<GrabRequest> lockById(long campusId, long id);

    /** 数据库当前时间；租约判定必须用它，不能用应用时钟。 */
    Instant databaseNow();

    /**
     * 正常执行路径的终态写入：要求 PROCESSING + token 匹配 + 租约未过期。
     * SUCCEEDED 要求租约仍在有效期内；EXPIRED 要求租约已过期。
     */
    boolean finish(long campusId, long id, String token, GrabRequestStatus status,
                   String resultCode, int httpStatus, String resultJson);

    /**
     * 恢复路径的终态写入：用于"业务已提交但请求行未终结"或"租约已过期"的修复。
     * 只要求当前仍是 PROCESSING，不再要求租约有效——否则崩溃后的请求永远无法收敛。
     */
    boolean finishRecovered(long campusId, long id, GrabRequestStatus status,
                            String resultCode, int httpStatus, String resultJson);

    /**
     * 受控补偿：执行线程在租约过期被恢复线程标 EXPIRED 之后才完成业务提交时，
     * 允许在“grab_record 已证明提交”的前提下把 EXPIRED 升级为 SUCCEEDED。
     * 只允许 EXPIRED 单向升级，不允许覆盖 SUCCEEDED/REJECTED。
     */
    boolean upgradeExpiredToSucceeded(long campusId, long id, String resultCode, int httpStatus, String resultJson);

    /** 扫描过期未终结的请求，供 worker 恢复任务收敛。 */
    List<GrabRequest> findExpired(int limit);
}
