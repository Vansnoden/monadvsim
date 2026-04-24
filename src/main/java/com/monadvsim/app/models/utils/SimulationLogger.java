package com.monadvsim.app.models.utils;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;


public class SimulationLogger {
    private static final Logger LOGGER = Logger.getLogger("Simulation");
    private static FileHandler fileHandler;
    private static ConsoleHandler consoleHandler;
    private static String currentLogFile;

    static {
        try {
            String logDir = "logs";
            new java.io.File(logDir).mkdirs();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            currentLogFile = logDir + "/simulation_" + timestamp + ".log";

            fileHandler = new FileHandler(currentLogFile, true);
            fileHandler.setFormatter(new SimpleFormatter() {
                @Override
                public String format(LogRecord record) {
                    return String.format("[%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS] [%2$s] %3$s %4$s%n",
                        record.getMillis(),
                        record.getLevel(),
                        record.getSourceClassName() != null ? record.getSourceClassName() : "",
                        record.getMessage());
                }
            });
            fileHandler.setLevel(Level.ALL);

            consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SimpleFormatter());
            consoleHandler.setLevel(Level.INFO);

            LOGGER.addHandler(fileHandler);
            LOGGER.addHandler(consoleHandler);
            LOGGER.setLevel(Level.ALL);
            LOGGER.setUseParentHandlers(false);

            LOGGER.info("=== Simulation Log Started ===");
            LOGGER.info("Log file: " + currentLogFile);

        } catch (IOException e) {
            SimulationLogger.severe("Failed to initialize file logger: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // printf‑style convenience methods
    // ------------------------------------------------------------------------
    public static void info(String format, Object... args) {
        LOGGER.info(String.format(format, args));
    }

    public static void warning(String format, Object... args) {
        LOGGER.warning(String.format(format, args));
    }

    public static void fine(String format, Object... args) {
        LOGGER.fine(String.format(format, args));
    }

    public static void severe(String format, Object... args) {
        LOGGER.severe(String.format(format, args));
    }

    // For when you already have a formatted string
    public static void info(String msg) { LOGGER.info(msg); }
    public static void warning(String msg) { LOGGER.warning(msg); }
    public static void fine(String msg) { LOGGER.fine(msg); }
    public static void severe(String msg) { LOGGER.severe(msg); }

    public static Logger getLogger() { return LOGGER; }
    public static String getCurrentLogFile() { return currentLogFile; }

    public static void close() {
        if (fileHandler != null) {
            fileHandler.flush();
            fileHandler.close();
        }
    }
}