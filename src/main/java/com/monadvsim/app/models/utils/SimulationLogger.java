package com.monadvsim.app.models.utils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

/**
 * Centralized logger.
 *
 * <p>Log level policy:
 * <ul>
 *   <li>Console — WARNING and above, so a normal run is quiet.</li>
 *   <li>File    — controlled by the {@code sim.logLevel} system property.
 *                 Defaults to INFO. Set {@code -Dsim.logLevel=FINE} to
 *                 capture the per-agent trace lines for debugging.</li>
 * </ul>
 *
 * <p>Hot-path messages (per-agent, per-lay, per-death, per-stage transition)
 * are logged at FINE and are therefore suppressed by default. Turn them on
 * only when diagnosing a specific run.
 */
public class SimulationLogger {

    private static final Logger LOGGER = Logger.getLogger("Simulation");
    private static FileHandler fileHandler;
    private static ConsoleHandler consoleHandler;
    private static String currentLogFile;
    private static String logDir = "logs";
    private static boolean initialized = false;

    // Single static block, guarded against re-entry.
    static {
        initialize("logs");
    }

    public static synchronized void initialize(String customLogDir) {
        if (initialized) {
            // Already initialized once; a second call is a no-op unless the
            // caller explicitly wants to change the directory.
            if (customLogDir == null || customLogDir.isEmpty() || customLogDir.equals(logDir)) {
                return;
            }
            // Fall through to reinit on a different dir.
        }

        try {
            if (customLogDir != null && !customLogDir.isEmpty()) {
                logDir = customLogDir;
            }

            // Tear down any existing handlers cleanly.
            if (fileHandler != null) {
                fileHandler.flush();
                fileHandler.close();
                LOGGER.removeHandler(fileHandler);
                fileHandler = null;
            }
            if (consoleHandler != null) {
                LOGGER.removeHandler(consoleHandler);
                consoleHandler = null;
            }

            new java.io.File(logDir).mkdirs();

            String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            currentLogFile = logDir + "/simulation_" + timestamp + ".log";

            // ---- File handler ------------------------------------------------
            // Default level INFO; override with -Dsim.logLevel=FINE
            Level fileLevel = parseLevel(
                    System.getProperty("sim.logLevel", "INFO"),
                    Level.INFO);

            fileHandler = new FileHandler(currentLogFile, true);
            fileHandler.setFormatter(new SimpleFormatter() {
                @Override
                public String format(LogRecord record) {
                    return String.format(
                        "[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%2$s] %3$s %4$s%n",
                        record.getMillis(),
                        record.getLevel(),
                        record.getSourceClassName() != null ? record.getSourceClassName() : "",
                        record.getMessage());
                }
            });
            fileHandler.setLevel(fileLevel);

            // ---- Console handler ---------------------------------------------
            // Default WARNING so a normal run doesn't spam stdout.
            Level consoleLevel = parseLevel(
                    System.getProperty("sim.consoleLogLevel", "WARNING"),
                    Level.WARNING);

            consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SimpleFormatter());
            consoleHandler.setLevel(consoleLevel);

            LOGGER.addHandler(fileHandler);
            LOGGER.addHandler(consoleHandler);
            // Logger level must be <= the minimum of the two handlers,
            // otherwise messages get dropped before they reach either one.
            LOGGER.setLevel(minLevel(fileLevel, consoleLevel));
            LOGGER.setUseParentHandlers(false);

            initialized = true;

            LOGGER.info("=== Simulation Log Started ===");
            LOGGER.info("Log file: " + currentLogFile);
            LOGGER.info("File level: " + fileLevel + ", console level: " + consoleLevel);

        } catch (IOException e) {
            System.err.println("Failed to initialize file logger: " + e.getMessage());
        }
    }

    private static Level parseLevel(String value, Level fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Level.parse(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Level minLevel(Level a, Level b) {
        return a.intValue() <= b.intValue() ? a : b;
    }

    // ------------------------------------------------------------------------
    // printf-style convenience methods
    // ------------------------------------------------------------------------

    public static void info(String format, Object... args) {
        if (LOGGER.isLoggable(Level.INFO)) LOGGER.info(String.format(format, args));
    }

    public static void warning(String format, Object... args) {
        if (LOGGER.isLoggable(Level.WARNING)) LOGGER.warning(String.format(format, args));
    }

    public static void fine(String format, Object... args) {
        if (LOGGER.isLoggable(Level.FINE)) LOGGER.fine(String.format(format, args));
    }

    public static void severe(String format, Object... args) {
        LOGGER.severe(String.format(format, args));
    }

    public static void info(String msg)    { if (LOGGER.isLoggable(Level.INFO))    LOGGER.info(msg); }
    public static void warning(String msg) { if (LOGGER.isLoggable(Level.WARNING)) LOGGER.warning(msg); }
    public static void fine(String msg)    { if (LOGGER.isLoggable(Level.FINE))    LOGGER.fine(msg); }
    public static void severe(String msg)  { LOGGER.severe(msg); }

    public static Logger getLogger() { return LOGGER; }
    public static String getCurrentLogFile() { return currentLogFile; }

    public static synchronized void close() {
        if (fileHandler != null) {
            fileHandler.flush();
            fileHandler.close();
        }
    }
}