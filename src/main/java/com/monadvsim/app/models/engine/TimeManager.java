package com.monadvsim.app.models.engine;

import java.time.LocalDateTime;
import java.time.Duration;

public class TimeManager {
    private final LocalDateTime startDateTime;
    private LocalDateTime currentDateTime;
    private final LocalDateTime endDateTime;
    
    private long tickCount = 0;
    private final Duration tickDuration;      // Usually 15 minutes
    private final Duration dataStepDuration;  // Usually 1 hour (for ERA5)

    /**
     * @param start The start date of the simulation
     * @param totalTicks Total number of ticks to run
     * @param tickMinutes Length of each tick in minutes (e.g., 15)
     */
    public TimeManager(LocalDateTime start, int totalTicks, int tickMinutes) {
        this.startDateTime = start;
        this.currentDateTime = start;
        this.tickDuration = Duration.ofMinutes(tickMinutes);
        this.dataStepDuration = Duration.ofHours(1); // Standard for ERA5-Land
        
        // Calculate end date based on total ticks
        this.endDateTime = start.plus(tickDuration.multipliedBy(totalTicks));
    }

    /**
     * Advances the simulation by one tick.
     * @return true if the simulation is still within the time bounds.
     */
    public boolean tick() {
        currentDateTime = currentDateTime.plus(tickDuration);
        tickCount++;
        return currentDateTime.isBefore(endDateTime);
    }

    /**
     * Maps the current simulation time to the index of the NetCDF climate frame.
     * If sim is at 45 mins and data is hourly, this returns index 0.
     * If sim is at 75 mins, this returns index 1.
     */
    public int getCurrentFrameIndex() {
        Duration elapsed = Duration.between(startDateTime, currentDateTime);
        return (int) (elapsed.toHours()); // Simple mapping for hourly data
    }

    /**
     * PhD Level Smoothing: 
     * Returns how far (0.0 to 1.0) we are between two hourly climate data points.
     * Useful if you want to interpolate temperature between two hours.
     */
    public double getInterpolationFactor() {
        long minutesIntoHour = currentDateTime.getMinute();
        return (double) minutesIntoHour / 60.0;
    }

    // Getters
    public LocalDateTime getCurrentDateTime() { return currentDateTime; }
    public long getTickCount() { return tickCount; }
    public LocalDateTime getStartDateTime() { return startDateTime; }
    public LocalDateTime getEndDateTime() { return endDateTime; }
    public Duration getTickDuration(){ return this.tickDuration; }
}