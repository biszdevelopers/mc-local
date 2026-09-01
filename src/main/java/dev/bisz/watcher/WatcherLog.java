package dev.bisz.watcher;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class WatcherLog {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private WatcherLog() {
    }

    public static void log(Level level, String message) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");

        String entry = "[%s] [%s] %s%n".formatted(
                TIMESTAMP_FORMAT.format(LocalDateTime.now()),
                level,
                message
        );
        MinecraftLogWindow.append(entry);
    }

    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
