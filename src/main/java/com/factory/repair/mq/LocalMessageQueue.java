package com.factory.repair.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component
public class LocalMessageQueue {

    private final Map<Topic, BlockingQueue<Message<?>>> queues = new ConcurrentHashMap<>();
    private final Map<Topic, List<MessageConsumer>> consumers = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private volatile boolean running = true;

    public void registerConsumer(MessageConsumer consumer) {
        consumers.computeIfAbsent(consumer.subscribedTopic(), k -> new CopyOnWriteArrayList<>())
                .add(consumer);
        log.info("注册消费者: topic={}, consumer={}", consumer.subscribedTopic(), consumer.getClass().getSimpleName());
    }

    @PostConstruct
    public void init() {
        for (Topic topic : Topic.values()) {
            queues.put(topic, new LinkedBlockingQueue<>(10000));
        }
        executor = Executors.newFixedThreadPool(Topic.values().length, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("mq-consumer-" + t.getId());
            return t;
        });
        for (Topic topic : Topic.values()) {
            executor.submit(() -> consumeLoop(topic));
        }
        log.info("本地消息队列已启动, topics={}", Topic.values().length);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        executor.shutdownNow();
        log.info("本地消息队列已关闭");
    }

    public <T> void publish(Message<T> message) {
        BlockingQueue<Message<?>> queue = queues.get(message.getTopic());
        if (queue != null) {
            if (!queue.offer(message)) {
                log.warn("消息队列已满，消息被丢弃: topic={}, messageId={}", message.getTopic(), message.getMessageId());
            }
        }
    }

    private void consumeLoop(Topic topic) {
        while (running) {
            try {
                Message<?> message = queues.get(topic).poll(1, TimeUnit.SECONDS);
                if (message == null) continue;

                List<MessageConsumer> topicConsumers = consumers.get(topic);
                if (topicConsumers == null || topicConsumers.isEmpty()) continue;

                for (MessageConsumer consumer : topicConsumers) {
                    try {
                        consumer.consume(message);
                    } catch (Exception e) {
                        if (message.canRetry()) {
                            message.incrementRetry();
                            queues.get(topic).offer(message);
                            log.warn("消费失败，将重试: topic={}, messageId={}, retry={}/{}",
                                    topic, message.getMessageId(), message.getRetryCount(), message.getMaxRetries(), e);
                        } else {
                            log.error("消费失败且已达最大重试次数: topic={}, messageId={}",
                                    topic, message.getMessageId(), e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
