package com.factory.repair.util;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SnowflakeIdGenerator {

    private final AtomicLong sequence = new AtomicLong(0);
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

    public String generateOrderNo() {
        String dateStr = LocalDate.now().format(formatter);
        long seq = sequence.incrementAndGet();
        return String.format("WO%s%05d", dateStr, seq % 100000);
    }
}
