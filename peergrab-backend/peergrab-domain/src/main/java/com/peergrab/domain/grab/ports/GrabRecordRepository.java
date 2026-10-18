package com.peergrab.domain.grab.ports;

import com.peergrab.domain.grab.model.GrabRecord;
import java.util.Optional;

public interface GrabRecordRepository {

    /**
     * 插入抢单记录。撞唯一索引时必须抛异常，由应用层捕获后判定为抢单失败。
     * 这是防超卖的最后一道防线，绝不能用 INSERT IGNORE 把冲突吞掉。
     */
    void insert(GrabRecord record);

    int countGrabbed(long campusId, long errandId);

    /**
     * 持久化的幂等结果。必须带 round：任务回退/流转后 round 自增，
     * 旧轮次的 requestId 记录不能冒充"本轮已抢中"。NULL request_id 的历史记录不会命中。
     */
    Optional<GrabRecord> findGrabbedByRequestId(long campusId, long errandId, String requestId, int round);
}
