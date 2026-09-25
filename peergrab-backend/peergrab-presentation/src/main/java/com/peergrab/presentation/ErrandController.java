package com.peergrab.presentation;

import com.peergrab.application.usecase.*;
import com.peergrab.application.usecase.query.ErrandActionResolver;
import com.peergrab.application.usecase.query.GetErrandDetailUseCase;
import com.peergrab.application.usecase.query.QueryErrandListUseCase;
import com.peergrab.domain.errand.model.Errand;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.domain.errand.ports.ErrandRepository;
import com.peergrab.presentation.auth.CurrentUser;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务接口：发布 / 列表 / 详情 / 全部生命周期操作。
 *
 * 身份不再从 X-User-Id 裸取，而是从 AuthInterceptor 解析出的 CurrentUser 拿。
 * 前端渲染的操作按钮由详情接口的 availableActions 驱动（见 ErrandActionResolver），
 * 前端不实现第二份状态机。
 */
@RestController
@RequestMapping("/api/errands")
public class ErrandController {

    private final PublishErrandUseCase publishUseCase;
    private final GrabErrandUseCase grabUseCase;
    private final ConfirmErrandUseCase confirmUseCase;
    private final PickUpErrandUseCase pickUpUseCase;
    private final DeliverErrandUseCase deliverUseCase;
    private final SettleErrandUseCase settleUseCase;
    private final RefundErrandUseCase refundUseCase;
    private final DisputeErrandUseCase disputeUseCase;
    private final ArbitrateErrandUseCase arbitrateUseCase;
    private final QueryErrandListUseCase queryUseCase;
    private final GetErrandDetailUseCase detailUseCase;
    private final ErrandActionResolver actionResolver;
    private final ErrandRepository errandRepository;
    private final ObjectMapper objectMapper;

    public ErrandController(PublishErrandUseCase publishUseCase,
                            GrabErrandUseCase grabUseCase,
                            ConfirmErrandUseCase confirmUseCase,
                            PickUpErrandUseCase pickUpUseCase,
                            DeliverErrandUseCase deliverUseCase,
                            SettleErrandUseCase settleUseCase,
                            RefundErrandUseCase refundUseCase,
                            DisputeErrandUseCase disputeUseCase,
                            ArbitrateErrandUseCase arbitrateUseCase,
                            QueryErrandListUseCase queryUseCase,
                            GetErrandDetailUseCase detailUseCase,
                            ErrandActionResolver actionResolver,
                            ErrandRepository errandRepository,
                            ObjectMapper objectMapper) {
        this.publishUseCase = publishUseCase;
        this.grabUseCase = grabUseCase;
        this.confirmUseCase = confirmUseCase;
        this.pickUpUseCase = pickUpUseCase;
        this.deliverUseCase = deliverUseCase;
        this.settleUseCase = settleUseCase;
        this.refundUseCase = refundUseCase;
        this.disputeUseCase = disputeUseCase;
        this.arbitrateUseCase = arbitrateUseCase;
        this.queryUseCase = queryUseCase;
        this.detailUseCase = detailUseCase;
        this.actionResolver = actionResolver;
        this.errandRepository = errandRepository;
        this.objectMapper = objectMapper;
    }

    public record PublishRequest(Long campusId, ErrandType type, String title,
                                 Long rewardCents, Integer slotTotal) {}

    @PostMapping
    public Result<PublishErrandUseCase.Result> publish(
            @RequestBody PublishRequest req,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long userId = CurrentUser.get();
        var cmd = new PublishErrandUseCase.Command(
                req.campusId() == null ? 1L : req.campusId(),
                userId,
                req.type() == null ? ErrandType.DELIVERY : req.type(),
                req.title(),
                req.rewardCents() == null ? 0L : req.rewardCents(),
                req.slotTotal() == null ? 1 : req.slotTotal(),
                requestId == null ? UUID.randomUUID().toString() : requestId);
        return Result.ok(publishUseCase.publish(cmd));
    }

    /** 任务广场：默认只看 PUBLISHED */
    @GetMapping
    public Result<Object> list(@RequestParam(defaultValue = "1") long campusId,
                               @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String cursor) {
        long viewer = CurrentUser.get();
        if (cursor != null) {
            var cursorPage = queryUseCase.listByCursor(campusId, status, cursor, size);
            return Result.ok(Map.of(
                    "items", cursorPage.items().stream().map(e -> toCard(e, viewer)).toList(),
                    "nextCursor", cursorPage.nextCursor() == null ? "" : cursorPage.nextCursor()));
        }
        return Result.ok(queryUseCase.list(campusId, status, page, size).stream()
                .map(e -> toCard(e, viewer))
                .toList());
    }

    /** 我的任务：我发布的 / 我抢的 */
    @GetMapping("/mine")
    public Result<List<Map<String, Object>>> mine(@RequestParam(defaultValue = "PUBLISHED_BY_ME") String role,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        long userId = CurrentUser.get();
        List<Errand> list = "GRABBED_BY_ME".equals(role)
                ? queryUseCase.myGrabbed(userId, page, size)
                : queryUseCase.myPublished(userId, page, size);
        return Result.ok(list.stream().map(e -> toCard(e, userId)).toList());
    }

    /**
     * 详情：带 availableActions，前端据此渲染按钮。
     *
     * 走缓存（P5）：任务的静态与状态字段来自 Cache Aside 链路；
     * availableActions 与 role 不进缓存——它们依赖"谁在看"，
     * 缓存进去会导致 A 用户看到 B 用户的按钮集合（缓存串号）。
     * 这是缓存设计里最容易出事的一类：**凡是与请求者身份相关的派生数据都不能进共享缓存**。
     */
    @GetMapping("/{errandId}")
    public Result<Map<String, Object>> detail(@PathVariable long errandId) {
        long viewer = CurrentUser.get();
        var cachedJson = detailUseCase.detailJson(errandId);
        if (cachedJson.isEmpty()) {
            return Result.fail(ErrorCode.ERRAND_NOT_FOUND);
        }
        // Shared cache stores identity-independent fields. Compute actions for this viewer
        // from those fields instead of loading the same task from MySQL on every hit.
        try {
            Map<String, Object> card = objectMapper.readValue(cachedJson.get(), new TypeReference<>() {});
            long publisherId = Long.parseLong(String.valueOf(card.get("publisherId")));
            long rawGrabberId = Long.parseLong(String.valueOf(card.get("grabberId")));
            var resolved = actionResolver.resolve(
                    ErrandStatus.valueOf(String.valueOf(card.get("status"))),
                    publisherId, rawGrabberId == -1L ? null : rawGrabberId, viewer);
            card.put("role", resolved.role());
            card.put("availableActions", resolved.actions());
            return Result.ok(card);
        } catch (JsonProcessingException | RuntimeException e) {
            // A malformed/old cache entry must not break task details.
            return errandRepository.findById(errandId)
                    .map(errand -> Result.ok(toCard(errand, viewer)))
                    .orElseGet(() -> Result.fail(ErrorCode.ERRAND_NOT_FOUND));
        }
    }

    /** 状态时间线（事件溯源的展示面） */
    @GetMapping("/{errandId}/timeline")
    public Result<List<Map<String, Object>>> timeline(@PathVariable long errandId) {
        CurrentUser.get();
        return Result.ok(queryUseCase.statusLog(errandId).stream()
                .map(c -> Map.<String, Object>of(
                        "time", c.time().toString(), "from", c.from(), "to", c.to(),
                        "round", c.round(), "operatorId", c.operatorId()))
                .toList());
    }

    /**
     * 抢单。requestId 由客户端传入做幂等，重试用同一个值。
     * 并发抢单演示时前端会同时发 N 个请求、各自带不同 requestId。
     */
    @PostMapping("/{errandId}/grab")
    public Result<GrabErrandUseCase.Result> grab(@PathVariable long errandId,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long runnerId = CurrentUser.get();
        var cmd = new GrabErrandUseCase.Command(
                errandId, runnerId,
                requestId == null ? UUID.randomUUID().toString() : requestId,
                60);
        GrabErrandUseCase.Result result = grabUseCase.grab(cmd);
        return result.grabbed() ? Result.ok(result) : Result.fail(result.code(), result);
    }

    @PostMapping("/{errandId}/confirm")
    public Result<Void> confirm(@PathVariable long errandId) {
        confirmUseCase.confirm(new ConfirmErrandUseCase.Command(errandId, CurrentUser.get()));
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/pickup")
    public Result<Void> pickup(@PathVariable long errandId) {
        pickUpUseCase.pickUp(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/deliver")
    public Result<Void> deliver(@PathVariable long errandId) {
        deliverUseCase.deliver(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    @PostMapping("/{errandId}/settle")
    public Result<Map<String, Object>> settle(@PathVariable long errandId) {
        var r = settleUseCase.settle(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/cancel")
    public Result<Map<String, Object>> cancel(@PathVariable long errandId) {
        var r = refundUseCase.cancelAndRefund(errandId, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    @PostMapping("/{errandId}/dispute")
    public Result<Void> dispute(@PathVariable long errandId) {
        disputeUseCase.raise(errandId, CurrentUser.get());
        return Result.ok(null);
    }

    public record ArbitrateRequest(String favor) {}

    @PostMapping("/{errandId}/arbitrate")
    public Result<Map<String, Object>> arbitrate(@PathVariable long errandId,
                                                 @RequestBody ArbitrateRequest req) {
        var favor = "PUBLISHER".equalsIgnoreCase(req.favor())
                ? ArbitrateErrandUseCase.Favor.PUBLISHER
                : ArbitrateErrandUseCase.Favor.RUNNER;
        var r = arbitrateUseCase.arbitrate(errandId, favor, CurrentUser.get());
        return Result.ok(Map.of("result", r.name()));
    }

    /** 统一的任务卡片：列表与详情共用，保证 availableActions 处处一致 */
    private Map<String, Object> toCard(Errand e, long viewer) {
        var resolved = actionResolver.resolve(e, viewer);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", e.id());
        card.put("title", e.title());
        card.put("status", e.status().name());
        card.put("type", e.type().name());
        card.put("rewardCents", e.reward().cents());
        card.put("slotTotal", e.slotTotal());
        card.put("slotTaken", e.slotTaken());
        card.put("publisherId", e.publisherId());
        card.put("grabberId", e.grabberId() == null ? -1L : e.grabberId());
        card.put("round", e.round());
        card.put("role", resolved.role());
        card.put("availableActions", resolved.actions());
        return card;
    }
}
