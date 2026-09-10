package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.utils.SimulationLogger;
import java.time.LocalDateTime;
import java.time.Duration;

/**
 * Simulation Time Controller
 */
public class TimeManager {
    
    private final LocalDateTime startDateTime;
    private LocalDateTime currentDateTime;
    private final LocalDateTime endDateTime;
    private LocalDateTime dataStartDateTime;
    
    private long tickCount = 0;
    private final Duration tickDuration;      
    private final Duration dataStepDuration;  
    private long totalTicks = 0;
    
    public TimeManager(LocalDateTime start, int totalTicks, int tickMinutes) {
        this.startDateTime = start;
        this.currentDateTime = start;
        this.tickDuration = Duration.ofMinutes(tickMinutes);
        this.dataStepDuration = Duration.ofHours(1);
        this.totalTicks = totalTicks;
        this.endDateTime = start.plus(tickDuration.multipliedBy(totalTicks));
        this.dataStartDateTime = null;
    }
    
    public void setDataStartDateTime(LocalDateTime dataStart) {
        this.dataStartDateTime = dataStart;
        SimulationLogger.info("[TimeManager] Data start time set to: %s", dataStart);
    }

//    public int getCurrentFrameIndex() {
//        // Use the data's absolute time if available
//        if (dataStartDateTime != null) {
//            // Calculate elapsed hours from data start to current time
//            Duration elapsedSinceDataStart = Duration.between(dataStartDateTime, currentDateTime);
//            long hours = elapsedSinceDataStart.toHours();
//
//            // If current time is before data start, return 0 (use first frame)
//            if (hours < 0) {
//                SimulationLogger.warning("[TimeManager] Current time %s is before data start %s, using frame 0",
//                    currentDateTime, dataStartDateTime);
//                return 0;
//            }
//
//            return (int) Math.max(0, hours);
//        }
//
//        // Fallback: use elapsed seconds from simulation start (original behavior)
//        Duration elapsed = Duration.between(startDateTime, currentDateTime);
//        long seconds = elapsed.getSeconds();
//        return (int) (seconds / 3600);
//    }

    public boolean tick() {
        currentDateTime = currentDateTime.plus(tickDuration);
        tickCount++;
        return currentDateTime.isBefore(endDateTime);
    }

    public double getInterpolationFactor() {
        long minutesIntoHour = currentDateTime.getMinute();
        return (double) minutesIntoHour / 60.0;
    }

    public LocalDateTime getCurrentDateTime() { return currentDateTime; }
    public long getTickCount() { return tickCount; }
    public LocalDateTime getStartDateTime() { return startDateTime; }
    public LocalDateTime getEndDateTime() { return endDateTime; }

    public long getTotalTicks() { return totalTicks; }
    public void setTotalTicks(long totalTicks) { this.totalTicks = totalTicks; }
    public long getTickMinutes() { return tickDuration.toMinutes(); }
    
//    public int getCurrentFrameIndex() {
//        Duration elapsed = Duration.between(startDateTime, currentDateTime);
//        long seconds = elapsed.getSeconds();
//        long offsetSeconds = 41400; // 11.5 hours alignment
//        return (int) ((seconds + offsetSeconds) / 3600);
//    }
    
    // In TimeManager
//    public int getCurrentFrameIndex(){
//        Duration elapsed = Duration.between(dataStartDateTime, currentDateTime);
//        return (int) (elapsed.toHours());
//    }
    // In TimeManager.java
    public int getCurrentFrameIndex() {
        // If dataStartDateTime is null, try to set it
        if (dataStartDateTime == null) {
            dataStartDateTime = startDateTime;
            SimulationLogger.warning("[TimeManager] dataStartDateTime was null, set to: %s", dataStartDateTime);
            return 0; // Return first frame
        }

        try {
            Duration elapsedSinceDataStart = Duration.between(dataStartDateTime, currentDateTime);
            long hours = elapsedSinceDataStart.toHours();

            if (hours < 0) {
                return 0; // Use first frame if before data start
            }

            return (int) Math.min(hours, Integer.MAX_VALUE);

        } catch (Exception e) {
            SimulationLogger.severe("[TimeManager] Error calculating frame index: " + e.getMessage());
            return 0;
        }
    }
}