package com.digitalt3.commons.api;

import java.util.Map;

public interface Logger {
    void debug(String message, Map<String, Object> context);
    void info(String message, Map<String, Object> context);
    void warn(String message, Map<String, Object> context);
    void error(String message, Throwable error, Map<String, Object> context);
    void flush();
}
