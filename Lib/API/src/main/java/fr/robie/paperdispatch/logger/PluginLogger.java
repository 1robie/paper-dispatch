package fr.robie.paperdispatch.logger;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal logging seam used by the library instead of calling
 * {@code plugin.getLogger()} directly.
 *
 * <p>Two reasons it exists: it lets consumers route library diagnostics wherever they like
 * (SLF4J, a custom channel, a prefixed logger), and it makes those diagnostics assertable in
 * tests — a plain {@link Logger} would otherwise have to be intercepted with a handler.
 *
 * <p>Wrap an existing JUL logger with {@link #of(Logger)}:
 * <pre>{@code
 * PluginLogger logger = PluginLogger.of(plugin.getLogger());
 * }</pre>
 *
 * <p>Being a {@link FunctionalInterface}, a lambda works too:
 * <pre>{@code
 * PluginLogger collecting = (level, message) -> captured.add(level + ": " + message);
 * }</pre>
 */
@FunctionalInterface
public interface PluginLogger {

    /**
     * Logs a message at the given level. The single abstract method every other method
     * on this interface delegates to.
     *
     * @param level   the severity
     * @param message the message
     */
    void log(Level level, String message);

    /**
     * Logs at {@link Level#SEVERE}.
     *
     * @param message the message
     */
    default void severe(String message) {
        this.log(Level.SEVERE, message);
    }

    /**
     * Logs at {@link Level#WARNING}.
     *
     * @param message the message
     */
    default void warning(String message) {
        this.log(Level.WARNING, message);
    }

    /**
     * Logs at {@link Level#INFO}.
     *
     * @param message the message
     */
    default void info(String message) {
        this.log(Level.INFO, message);
    }

    /**
     * Logs at {@link Level#FINE}. Typically hidden unless the server raises its log level.
     *
     * @param message the message
     */
    default void fine(String message) {
        this.log(Level.FINE, message);
    }

    /**
     * Adapts a standard {@link Logger} — usually {@code plugin.getLogger()}.
     *
     * @param logger the logger to delegate to
     * @return a {@link PluginLogger} backed by {@code logger}
     */
    static PluginLogger of(Logger logger) {
        return logger::log;
    }
}
