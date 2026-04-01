package com.titan.notifications.repository;

import com.titan.notifications.model.NotificationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface NotificationAuditRepository extends JpaRepository<NotificationAudit, Long> {
    List<NotificationAudit> findByTransactionId(String transactionId);
    List<NotificationAudit> findByAccountId(String accountId);
}
