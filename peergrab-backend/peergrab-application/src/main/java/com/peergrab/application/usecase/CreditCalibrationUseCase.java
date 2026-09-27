package com.peergrab.application.usecase;

import com.peergrab.domain.credit.ports.CreditRepository;
import org.springframework.stereotype.Service;

/**
 * 信用分快照校准。
 *
 * 业务事务与每日校准都按相同的事件顺序回放最近 30 天，
 * 每一步裁剪到 [0,100]；每日执行还会移除过期事件的历史影响。
 */
@Service
public class CreditCalibrationUseCase {

    private final CreditRepository creditRepository;

    public CreditCalibrationUseCase(CreditRepository creditRepository) {
        this.creditRepository = creditRepository;
    }

    public int calibrate(int windowDays, int limit) {
        return creditRepository.calibrateScores(Math.max(windowDays, 1), Math.max(limit, 1));
    }
}
