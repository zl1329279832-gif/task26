package com.maintenance.infrastructure.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction-aware wrapper around LocalMessageQueue.
 * <p>
 * When called inside an active Spring transaction, events are buffered and
 * published <b>after</b> the transaction commits successfully. This prevents
 * consumers from reading uncommitted (and potentially rolled-back) data.
 * <p>
 * When called outside a transaction, events are published immediately
 * (same as calling {@link LocalMessageQueue#publish} directly).
 */
@Slf4j
@Component
public class TransactionAwareEventPublisher {

    private final LocalMessageQueue messageQueue;

    public TransactionAwareEventPublisher(LocalMessageQueue messageQueue) {
        this.messageQueue = messageQueue;
    }

    /**
     * Publish an event. If a Spring transaction is active, the event is
     * deferred until after commit; otherwise it is published immediately.
     */
    public void publish(String eventType, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messageQueue.publish(eventType, payload);
                }
            });
            log.debug("Event [{}] registered for post-commit publish", eventType);
        } else {
            messageQueue.publish(eventType, payload);
        }
    }

    /**
     * Publish with explicit eventId for deduplication. Deferred if inside a transaction.
     */
    public void publishWithId(String eventId, String eventType, Object payload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messageQueue.publishWithId(eventId, eventType, payload);
                }
            });
            log.debug("Event [{}] (id={}) registered for post-commit publish", eventType, eventId);
        } else {
            messageQueue.publishWithId(eventId, eventType, payload);
        }
    }
}
