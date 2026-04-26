package com.monadvsim.app.models.engine;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.time.LocalDateTime;
import java.time.Duration;


/**
 * Simulation Time Controller
 *
 * Manages simulation time progression (15-minute ticks)
 *
 * Tracks current date/time, tick count, and total simulation duration
 *
 * Provides frame indexing for climate data interpolation
 *
 * Converts between simulation time and data time steps
 * 
 * 
 * @author void
 */

public class TimeManager {
    
    private final LocalDateTime startDateTime;
    private LocalDateTime currentDateTime;
    private final LocalDateTime endDateTime;
    
    private long tickCount = 0;
    private final Duration tickDuration;      // Usually 15 minutes
    private final Duration dataStepDuration;  // Usually 1 hour (for ERA5)
    private long totalTicks = 0;
    
    public TimeManager(LocalDateTime start, int totalTicks, int tickMinutes) {
        this.startDateTime = start;
        this.currentDateTime = start;
        this.tickDuration = Duration.ofMinutes(tickMinutes);
        this.dataStepDuration = Duration.ofHours(1); // Standard for ERA5-Land
        this.totalTicks = totalTicks;
        // Calculate end date based on total ticks
        this.endDateTime = start.plus(tickDuration.multipliedBy(totalTicks));
    }

    public boolean tick() {
        /*Update current tick and Date time*/
        currentDateTime = currentDateTime.plus(tickDuration);
        tickCount++;
        return currentDateTime.isBefore(endDateTime);
    }

    public int getCurrentFrameIndex() {
        /*Get current frame index*/
        Duration elapsed = Duration.between(startDateTime, currentDateTime);
        return (int) (elapsed.toHours()); // Simple mapping for hourly data
    }

    public double getInterpolationFactor() {
        long minutesIntoHour = currentDateTime.getMinute();
        return (double) minutesIntoHour / 60.0;
    }

    public LocalDateTime getCurrentDateTime() { return currentDateTime; }
    public long getTickCount() { return tickCount; }
    public LocalDateTime getStartDateTime() { return startDateTime; }
    public LocalDateTime getEndDateTime() { return endDateTime; }

    public long getTotalTicks() {
        return totalTicks;
    }

    public void setTotalTicks(long totalTicks) {
        this.totalTicks = totalTicks;
    }
    
    public long getTickMinutes() {
        return tickDuration.toMinutes();
    }
    
}