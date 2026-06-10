package com.factory.repair.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LocalMessageQueueTest {

    private LocalMessageQueue queue;

    @BeforeEach
    void setUp() {
        queue = new LocalMessageQueue();
        queue.init();
    }

    @Test
    @DisplayName("发布和消费消息")
    void testPublishAndConsume() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        queue.registerConsumer(new MessageConsumer() {
            @Override
            public Topic subscribedTopic() {
                return Topic.WORK_ORDER_EVENT;
            }

            @Override
            public void consume(Message<?> message) {
                received.set(message.getEventType());
                latch.countDown();
            }
        });

        Message<String> msg = Message.of(Topic.WORK_ORDER_EVENT, "TEST_EVENT", "hello");
        queue.publish(msg);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("TEST_EVENT", received.get());
    }

    @Test
    @DisplayName("消费失败后重试")
    void testRetryOnFailure() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        queue.registerConsumer(new MessageConsumer() {
            @Override
            public Topic subscribedTopic() {
                return Topic.SPARE_PART_EVENT;
            }

            @Override
            public void consume(Message<?> message) {
                int attempt = attempts.incrementAndGet();
                latch.countDown();
                if (attempt == 1) {
                    throw new RuntimeException("模拟消费失败");
                }
            }
        });

        Message<String> msg = Message.of(Topic.SPARE_PART_EVENT, "RETRY_TEST", "data");
        queue.publish(msg);

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(attempts.get() >= 2);
    }

    @Test
    @DisplayName("不同Topic的消息隔离")
    void testTopicIsolation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger wrongTopicCount = new AtomicInteger(0);

        queue.registerConsumer(new MessageConsumer() {
            @Override
            public Topic subscribedTopic() {
                return Topic.NOTIFICATION_EVENT;
            }

            @Override
            public void consume(Message<?> message) {
                latch.countDown();
            }
        });

        queue.registerConsumer(new MessageConsumer() {
            @Override
            public Topic subscribedTopic() {
                return Topic.AUDIT_LOG_EVENT;
            }

            @Override
            public void consume(Message<?> message) {
                wrongTopicCount.incrementAndGet();
            }
        });

        queue.publish(Message.of(Topic.NOTIFICATION_EVENT, "TEST", "data"));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(500); // 等待确认其他topic没收到
        assertEquals(0, wrongTopicCount.get());
    }

    @Test
    @DisplayName("Message工厂方法")
    void testMessageFactory() {
        Message<String> msg = Message.of(Topic.WORK_ORDER_EVENT, "CREATE", "payload");

        assertNotNull(msg.getMessageId());
        assertEquals(Topic.WORK_ORDER_EVENT, msg.getTopic());
        assertEquals("CREATE", msg.getEventType());
        assertEquals("payload", msg.getPayload());
        assertEquals(0, msg.getRetryCount());
        assertEquals(3, msg.getMaxRetries());
        assertTrue(msg.canRetry());

        msg.incrementRetry();
        msg.incrementRetry();
        msg.incrementRetry();
        assertFalse(msg.canRetry());
    }
}
