package com.factory.repair.mq;

public interface MessageConsumer {
    Topic subscribedTopic();
    void consume(Message<?> message);
}
