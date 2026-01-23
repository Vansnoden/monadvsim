package com.monadvsim.app.models.engine;


import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class TimeManager {
    private LocalDateTime startDateTime;
    private LocalDateTime currentDateTime;
    private LocalDateTime endDateTime;
    
    private long tickCount = 0;
    private final Duration tickDuration; // e.g., Duration.ofMinutes(15)
    
    // For Time-Series Mapping
    private final Duration dataStepDuration; // e.g., Duration.ofHours(1) for ERA5 data

    public TimeManager(LocalDateTime start, LocalDateTime end, Duration tickSize, Duration dataStep) {
        this.startDateTime = start;
        this.currentDateTime = start;
        this.endDateTime = end;
        this.tickDuration = tickSize;
        this.dataStepDuration = dataStep;
    }

    /**
     * Advances the simulation by one tick.
     * @return true if the simulation is still within the end date.
     */
    public boolean tick() {
        currentDateTime = currentDateTime.plus(tickDuration);
        tickCount++;
        return currentDateTime.isBefore(endDateTime);
    }

    /**
     * Calculates which frame of a Time Series the simulation is currently in.
     * Useful for fetching the correct 'band' in a multi-temporal Raster.
     */
    public int getCurrentFrameIndex() {
        long minutesElapsed = Duration.between(startDateTime, currentDateTime).toMinutes();
        long stepMinutes = dataStepDuration.toMinutes();
        return (int) (minutesElapsed / stepMinutes);
    }

    /**
     * Theoretical Interpolation Weight: 
     * Returns a value 0.0 to 1.0 representing how far we are between two data steps.
     * Used for smoothing temperature transitions.
     */
    public double getInterpolationFactor() {
        long minutesSinceLastStep = Duration.between(startDateTime, currentDateTime).toMinutes() % dataStepDuration.toMinutes();
        return (double) minutesSinceLastStep / dataStepDuration.toMinutes();
    }

    // Getters
    public LocalDateTime getCurrentDateTime() { return currentDateTime; }
    public long getTickCount() { return tickCount; }
    public String getTimestampString() { return currentDateTime.toString(); }
}