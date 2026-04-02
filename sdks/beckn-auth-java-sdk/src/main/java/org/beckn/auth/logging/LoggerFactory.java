package org.beckn.auth.logging;

/**
 * Factory that auto-detects and creates the best available {@link Logger}
 * implementation on the classpath.
 * <p>
 * Detection order:
 * <ol>
 * <li>If {@code org.slf4j.LoggerFactory} is present, returns an {@link Slf4jLogger}
 * (standard for Spring/Logback)</li>
 * </ol>
 * </p>
 *
 * @throws IllegalStateException if no suitable logger implementation is found
 */
public final class LoggerFactory {

    private static final String SLF4J_CLASS_NAME = "org.slf4j.LoggerFactory";

    private LoggerFactory() {
        // Utility class — no instantiation
    }

    /**
     * Creates the best available {@link Logger} implementation.
     *
     * @param clazz the class for which to create the logger
     * @return a ready-to-use {@link Logger} instance
     * @throws IllegalStateException if SLF4J is not found on the classpath
     */
    public static Logger createLogger(Class<?> clazz) {
        if (isSlf4jAvailable()) {
            return new Slf4jLogger(clazz);
        }
        throw new IllegalStateException(
                "No suitable Logger implementation found on classpath. " +
                "Please add 'slf4j-api' to your dependencies.");
    }

    /**
     * Checks if SLF4J is available on the classpath via reflection.
     *
     * @return {@code true} if SLF4J is present on the classpath
     */
    private static boolean isSlf4jAvailable() {
        try {
            Class.forName(SLF4J_CLASS_NAME);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
