package com.peergrab.application.usecase;

import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandCachePort;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.domain.errand.ports.PublishRequestRepository;
import com.peergrab.domain.grab.ports.GrabSlotPort;
import com.peergrab.domain.wallet.model.AccountType;
import com.peergrab.domain.wallet.model.EscrowOrder;
import com.peergrab.domain.wallet.model.LedgerEntry;
import com.peergrab.domain.wallet.model.WalletAccount;
import com.peergrab.domain.wallet.ports.WalletRepository;
import com.peergrab.shared.BizException;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Money;
import com.peergrab.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 发布任务用例：一个本地事务内完成资金托管与任务发布。
 *
 * 这是模块化单体最大的红利——托管扣款、复式记账、托管单、任务状态
 * 全在同一个数据库事务里，不需要任何分布式事务。
 * 拆微服务后这一段就必须改成 Seata TCC 或 Saga 补偿（见架构文档第 12 节）。
 *
 * 事务边界画在应用层而不是领域层：领域对象只负责业务规则，
 * 不知道"事务"这个技术概念的存在。
 */
@Service
public class PublishErrandUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishErrandUseCase.class);
    /** 名额键 TTL 给足，避免任务还没被抢完 Redis 键就过期了 */
    private static final long SLOT_TTL_SECONDS = 7 * 24 * 3600;
    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final ErrandRepository errandRepository;
    private final PublishRequestRepository publishRequests;
    private final WalletRepository walletRepository;
    private final GrabSlotPort grabSlotPort;
    private final SnowflakeIdGenerator idGenerator;
    private final ErrandCachePort cache;
    private final CacheEvictSupport cacheEvict;

    public PublishErrandUseCase(ErrandRepository errandRepository,
                                PublishRequestRepository publishRequests,
                                WalletRepository walletRepository,
                                GrabSlotPort grabSlotPort,
                                SnowflakeIdGenerator idGenerator,
                                ErrandCachePort cache,
                                CacheEvictSupport cacheEvict) {
        this.errandRepository = errandRepository;
        this.publishRequests = publishRequests;
        this.walletRepository = walletRepository;
        this.grabSlotPort = grabSlotPort;
        this.idGenerator = idGenerator;
        this.cache = cache;
        this.cacheEvict = cacheEvict;
    }

    public record Command(long campusId, long publisherId, ErrandType type,
                          String title, long rewardCents, int slotTotal, String requestId) {
        /** Existing in-process callers without a key keep their non-retryable behavior. */
        public Command(long campusId, long publisherId, ErrandType type,
                       String title, long rewardCents, int slotTotal) {
            this(campusId, publisherId, type, title, rewardCents, slotTotal,
                    UUID.randomUUID().toString());
        }
    }

    public record Result(long errandId, ErrandStatus status, long frozenCents) {}

    /**
     * rollbackFor 必须显式写 Exception.class：
     * Spring 默认只对 RuntimeException 回滚，checked 异常不会触发回滚，
     * 这是 @Transactional 六种失效场景里最常见的一种。
     */
    @Transactional(rollbackFor = Exception.class)
    public Result publish(Command cmd) {
        // A task has one current runner, one confirmation window and one escrow order.
        // Until those models support multiple independent runners, reject extra slots.
        if (cmd.slotTotal() != 1) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "slotTotal must be 1");
        }
        if (cmd.campusId() <= 0 || cmd.publisherId() <= 0 || cmd.type() == null
                || cmd.title() == null || cmd.title().isBlank()
                || cmd.title().length() > 64 || cmd.rewardCents() <= 0
                || !validRequestId(cmd.requestId())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "invalid errand details");
        }
        Money reward = Money.ofCents(cmd.rewardCents());
        String payloadHash = payloadHash(cmd);
        PublishRequestRepository.Claim claim = publishRequests.claimAndLock(
                cmd.publisherId(), cmd.requestId(), payloadHash);
        if (!payloadHash.equals(claim.payloadHash())) {
            throw new BizException(ErrorCode.DUPLICATE_REQUEST,
                    "X-Request-Id reused with different publish details");
        }
        if (claim.errandId() != null) {
            return new Result(claim.errandId(), ErrandStatus.PUBLISHED, reward.cents());
        }

        // The claim's unique key and row lock are acquired before any wallet row.
        // Same-key competitors wait for this transaction, then replay its committed ID.
        long errandId = idGenerator.nextId();

        WalletAccount publisherAccount = walletRepository
                .findByOwner(cmd.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "publisher=" + cmd.publisherId()));
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "escrow account missing"));

        // 同一事务中按 ID 锁账户，再转账和写借贷流水。
        String bizNo = LedgerEntry.escrowBizNo(errandId);
        WalletPosting.post(walletRepository, idGenerator, bizNo, LedgerEntry.RefType.ESCROW,
                errandId, List.of(
                        WalletPosting.Leg.debit(publisherAccount.id(), cmd.publisherId(), reward),
                        WalletPosting.Leg.credit(escrowAccount.id(), cmd.publisherId(), reward)));

        // 3. 托管单置 HELD
        walletRepository.insertEscrow(EscrowOrder.held(
                idGenerator.nextId(), cmd.campusId(), errandId, cmd.publisherId(), reward));

        // 4. 任务落库并发布。先 DRAFT 再 CAS 到 PUBLISHED，
        //    这样状态机的 DRAFT -> PUBLISHED 流转在数据库里也有真实痕迹
        Errand errand = Errand.draft(errandId, cmd.campusId(), cmd.publisherId(),
                cmd.type(), cmd.title(), reward, cmd.slotTotal());
        errandRepository.insert(errand);

        long versionBeforePublish = errand.version();
        errand.publish(versionBeforePublish);
        int published = errandRepository.casPublish(errandId, versionBeforePublish);
        if (published == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "publish failed, errandId=" + errandId);
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DRAFT, ErrandStatus.PUBLISHED,
                0, cmd.publisherId());

        if (publishRequests.complete(cmd.publisherId(), cmd.requestId(), errandId) != 1) {
            throw new IllegalStateException("publish claim was not completed: " + cmd.requestId());
        }

        // Redis is a hint. Database CAS handles a missing slot and DB lookup handles a
        // missing Bloom entry, so both external writes can happen after the DB commits.
        // This also removes network waits from the transaction that holds wallet locks.
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("publish requires transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Errand committed = null;
                try {
                    committed = errandRepository.findById(errandId).orElse(null);
                } catch (RuntimeException e) {
                    log.warn("published task committed but post-commit status lookup failed errandId={}",
                            errandId, e);
                }
                try {
                    if (committed != null && committed.status() == ErrandStatus.PUBLISHED
                            && committed.slotAvailable()) {
                        grabSlotPort.initSlot(errandId, cmd.slotTotal(), SLOT_TTL_SECONDS);
                    }
                } catch (RuntimeException e) {
                    log.warn("published task committed but Redis slot init failed errandId={}",
                            errandId, e);
                }
                try {
                    if (committed != null && !cache.registerExisting(errandId)) {
                        log.warn("published task committed but Bloom registration was deferred errandId={}",
                                errandId);
                    }
                } catch (RuntimeException e) {
                    log.warn("published task committed but Bloom registration failed errandId={}",
                            errandId, e);
                }
            }
        });
        // A previous read could have cached this new ID as absent.
        cacheEvict.evictAfterCommit(errandId);

        return new Result(errandId, ErrandStatus.PUBLISHED, reward.cents());
    }

    static String payloadHash(Command cmd) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String canonical = cmd.campusId() + "\u0000" + cmd.type().name() + "\u0000"
                    + cmd.rewardCents() + "\u0000" + cmd.slotTotal() + "\u0000" + cmd.title();
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean validRequestId(String requestId) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 128) return false;
        for (int i = 0; i < requestId.length(); i++) {
            char value = requestId.charAt(i);
            if (value < 0x21 || value > 0x7e) return false;
        }
        return true;
    }
}
