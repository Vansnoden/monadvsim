package com.monadvsim.app.models.engine;

import java.time.LocalDateTime;
import java.time.Duration;

/**
 * Simulation Time Controller
 */
public class TimeManager {
    
    private final LocalDateTime startDateTime;
    private LocalDateTime currentDateTime;
    private final LocalDateTime endDateTime;
    
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
    }

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
    public int getCurrentFrameIndex() {
        Duration elapsed = Duration.between(startDateTime, currentDateTime);
        long seconds = elapsed.getSeconds();
        // Use the actual data step duration (1 hour) and align to the data start
        return (int) (seconds / 3600);
    }
}