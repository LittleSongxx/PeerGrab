package com.peergrab.presentation;

import com.peergrab.application.usecase.query.QueryWalletUseCase;
import com.peergrab.presentation.auth.CurrentUser;
import com.peergrab.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final QueryWalletUseCase queryUseCase;

    public WalletController(QueryWalletUseCase queryUseCase) {
        this.queryUseCase = queryUseCase;
    }

    @GetMapping
    public Result<Map<String, Object>> balance() {
        long userId = CurrentUser.get();
        var b = queryUseCase.balance(userId);
        return Result.ok(Map.of("availableCents", b.availableCents(), "frozenCents", b.frozenCents()));
    }

    @GetMapping("/ledger")
    public Result<Object> ledger(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) String cursor) {
        long userId = CurrentUser.get();
        if (cursor != null) {
            var result = queryUseCase.ledgerByCursor(userId, cursor, size);
            return Result.ok(Map.of(
                    "items", result.items().stream().map(WalletController::toLedger).toList(),
                    "nextCursor", result.nextCursor() == null ? "" : result.nextCursor()));
        }
        return Result.ok(queryUseCase.ledger(userId, page, size).stream()
                .map(WalletController::toLedger).toList());
    }

    private static Map<String, Object> toLedger(com.peergrab.domain.wallet.ports.WalletQueryPort.LedgerView v) {
        // 账户行可能缺失，accountType 为 null；Map.of 不允许 null，这里用有序 Map 兜底。
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", String.valueOf(v.id()));
        row.put("time", v.time().toString());
        row.put("direction", v.direction());
        row.put("amountCents", v.amountCents());
        row.put("refType", v.refType());
        row.put("refId", String.valueOf(v.refId()));
        row.put("bizNo", v.bizNo());
        row.put("accountType", v.accountType() == null ? "UNKNOWN" : v.accountType());
        return row;
    }
}
