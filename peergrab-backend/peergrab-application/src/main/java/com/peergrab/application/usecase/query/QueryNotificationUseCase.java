package com.peergrab.application.usecase.query;

import com.peergrab.domain.notify.ports.NotificationQueryPort;
import com.peergrab.domain.notify.ports.NotificationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 站内消息查询：列表 + 未读数 */
@Service
public class QueryNotificationUseCase {

    private final NotificationQueryPort queryPort;
    private final NotificationRepository repository;

    public QueryNotificationUseCase(NotificationQueryPort queryPort, NotificationRepository repository) {
        this.queryPort = queryPort;
        this.repository = repository;
    }

    public List<NotificationQueryPort.NotificationView> list(long userId, int page, int size) {
        return queryPort.list(userId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public int unread(long userId) {
        return queryPort.unreadCount(userId);
    }

    public void markRead(long userId, long notificationId) {
        repository.markRead(userId, notificationId);
    }
}
