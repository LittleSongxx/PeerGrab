package com.peergrab.worker;

import com.peergrab.application.usecase.CreditCalibrationUseCase;
import com.peergrab.domain.credit.model.CreditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 信用分快照每日校准。
 *
 * 增量计分保证业务事务内无中间态；每日校准负责把 30 天窗口外的历史贡献移除。
 */
@Component
public class CreditCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CreditCalibrationJob.class);

    private final CreditCalibrationUseCase useCase;
    private final int batchSize;

    public CreditCalibrationJob(CreditCalibrationUseCase useCase,
                                @Value("${peergrab.credit.calibration-batch-size:1000}") int batchSize) {
        this.useCase = useCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${peergrab.credit.calibration-cron:0 0 3 * * ?}", scheduler = "maintenanceTaskScheduler")
    public void calibrate() {
        int total = 0;
        int changed;
        int batches = 0;
        do {
            changed = useCase.calibrate(CreditEventType.WINDOW_DAYS, batchSize);
            total += changed;
            batches++;
        } while (changed == batchSize && batches < 1000);
        if (batches == 1000 && changed == batchSize) {
            log.warn("信用分快照校准达到单轮批次上限，剩余数据留待下一轮");
        }
        if (total > 0) log.info("信用分快照校准完成 changed={} batches={}", total, batches);
    }
}
