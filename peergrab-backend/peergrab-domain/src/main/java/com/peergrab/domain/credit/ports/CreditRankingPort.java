package com.peergrab.domain.credit.ports;

import java.util.List;

/**
 * 校区信用分排行榜（Redis ZSET）。
 *
 * ZSET 的 score 就是信用分：ZADD 天然幂等（同 member 覆盖），
 * ZREVRANGE 直接出 Top N，不需要自己排序。
 */
public interface CreditRankingPort {

    void update(long campusId, long userId, int score);

    /** 从 MySQL 投影重建某校区榜单；空列表会清除旧榜单。 */
    void replace(long campusId, List<Entry> entries);

    /** Top N：返回 userId 与分数，按分数降序 */
    List<Entry> top(long campusId, int limit);

    record Entry(long userId, int score) {}
}
