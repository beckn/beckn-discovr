package org.beckn.auth.logging;

import org.slf4j.LoggerFactory;

/**
 * SLF4J adapter for the Beckn Auth SDK logger.
 * <p>
 * This class is only used if SLF4J is detected on the classpath at runtime.
 * </p>
 */
public final class Slf4jLogger implements Logger {

    private final org.slf4j.Logger slf4j;

    /**
     * Constructs a new Slf4jLogger.
     *
     * @param clazz the class for which to create a logger
     */
    public Slf4jLogger(Class<?> clazz) {
        this.slf4j = LoggerFactory.getLogger(clazz);
    }

    @Override
    public void info(String message) {
        slf4j.info(message);
    }

    @Override
    public void error(String message) {
        slf4j.error(message);
    }

    @Override
    public void error(String message, Throwable throwable) {
        slf4j.error(message, throwable);
    }

    @Override
    public void debug(String message) {
        slf4j.debug(message);
    }

    @Override
    public void warn(String message) {
        slf4j.warn(message);
    }
}
