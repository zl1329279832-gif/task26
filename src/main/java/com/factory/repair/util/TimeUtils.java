package com.factory.repair.util;

import java.time.Duration;
import java.time.LocalDateTime;

public final class TimeUtils {

    private TimeUtils() {}

    public static int minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return 0;
        return (int) Duration.between(start, end).toMinutes();
    }

    public static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
