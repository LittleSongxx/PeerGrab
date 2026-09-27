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
        return Map.of(
                "id", String.valueOf(v.id()), "time", v.time().toString(),
                "direction", v.direction(), "amountCents", v.amountCents(),
                "refType", v.refType(), "refId", String.valueOf(v.refId()),
                "bizNo", v.bizNo());
    }
}
