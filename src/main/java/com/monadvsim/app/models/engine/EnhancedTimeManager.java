package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.netcdf.NetCDFClimateReader;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Enhanced TimeManager with NetCDF time synchronization
 */
public class EnhancedTimeManager extends TimeManager {
    private final Map<LocalDateTime, Integer> timeIndexMap = new ConcurrentSkipListMap<>();
    private final List<LocalDateTime> netcdfTimes = new ArrayList<>();
    private final NetCDFClimateReader netcdfReader;
    private Duration netcdfTimeStep;
    private boolean synchronizedWithNetCDF = false;
    
    // Interpolation cache
    private final Map<LocalDateTime, InterpolationWeights> interpolationCache = 
        new ConcurrentSkipListMap<>();
    private final int interpolationCacheSize = 1000;
    
    public EnhancedTimeManager(LocalDateTime start, int totalTicks, int tickMinutes,
                              NetCDFClimateReader netcdfReader) {
        super(start, totalTicks, tickMinutes);
        this.netcdfReader = netcdfReader;
        this.netcdfTimeStep = Duration.ofHours(1); // Default for ERA5
        
        initializeNetCDFSynchronization();
    }
    
    private void initializeNetCDFSynchronization() {
        try {
            // Load NetCDF time values
            netcdfTimes.addAll(netcdfReader.getTimeValues());
            
            if (netcdfTimes.isEmpty()) {
                System.err.println("Warning: NetCDF file has no time values");
                return;
            }
            
            // Create time index map for quick lookup
            for (int i = 0; i < netcdfTimes.size(); i++) {
                timeIndexMap.put(netcdfTimes.get(i), i);
            }
            
            // Calculate NetCDF time step from first two times
            if (netcdfTimes.size() > 1) {
                netcdfTimeStep = Duration.between(netcdfTimes.get(0), netcdfTimes.get(1));
                System.out.println("NetCDF time step: " + netcdfTimeStep);
            }
            
            // Validate simulation time range against NetCDF
            validateTimeRange();
            
            synchronizedWithNetCDF = true;
            System.out.println("✓ TimeManager synchronized with NetCDF data");
            
        } catch (Exception e) {
            System.err.println("Failed to synchronize with NetCDF: " + e.getMessage());
            System.err.println("Falling back to basic time management");
        }
    }
    
    private void validateTimeRange() {
        LocalDateTime simStart = getStartDateTime();
        LocalDateTime simEnd = getEndDateTime();
        LocalDateTime netcdfStart = netcdfTimes.get(0);
        LocalDateTime netcdfEnd = netcdfTimes.get(netcdfTimes.size() - 1);
        
        if (simStart.isBefore(netcdfStart)) {
            System.err.printf("Warning: Simulation start (%s) is before NetCDF start (%s)%n",
                simStart, netcdfStart);
        }
        
        if (simEnd.isAfter(netcdfEnd)) {
            System.err.printf("Warning: Simulation end (%s) is after NetCDF end (%s)%n",
                simEnd, netcdfEnd);
            
            // Adjust simulation duration if needed
            long availableSteps = ChronoUnit.HOURS.between(simStart, netcdfEnd) * 
                                 (60 / getTickDuration().toMinutes());
            System.err.printf("Available simulation steps: %d%n", availableSteps);
        }
    }
    
    @Override
    public int getCurrentFrameIndex() {
        if (!synchronizedWithNetCDF) {
            return super.getCurrentFrameIndex();
        }
        
        LocalDateTime currentTime = getCurrentDateTime();
        
        // Find nearest NetCDF time step
        Integer exactIndex = timeIndexMap.get(currentTime);
        if (exactIndex != null) {
            return exactIndex;
        }
        
        // Find bracketing times for interpolation
        Map.Entry<LocalDateTime, Integer> floorEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).floorEntry(currentTime);
        Map.Entry<LocalDateTime, Integer> ceilingEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).ceilingEntry(currentTime);
        
        if (floorEntry == null && ceilingEntry == null) {
            // Outside NetCDF time range
            return clampToValidRange(super.getCurrentFrameIndex());
        }
        
        if (floorEntry == null) {
            // Before first NetCDF time
            return ceilingEntry.getValue();
        }
        
        if (ceilingEntry == null) {
            // After last NetCDF time
            return floorEntry.getValue();
        }
        
        // Between two NetCDF times - use floor (previous) index
        // Interpolation will be handled separately
        return floorEntry.getValue();
    }
    
    /**
     * Enhanced interpolation factor for temporal interpolation
     */
    @Override
    public double getInterpolationFactor() {
        if (!synchronizedWithNetCDF) {
            return super.getInterpolationFactor();
        }
        
        LocalDateTime currentTime = getCurrentDateTime();
        
        // Check cache first
        InterpolationWeights cached = interpolationCache.get(currentTime);
        if (cached != null) {
            return cached.factor;
        }
        
        // Calculate interpolation between NetCDF time steps
        Map.Entry<LocalDateTime, Integer> floorEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).floorEntry(currentTime);
        Map.Entry<LocalDateTime, Integer> ceilingEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).ceilingEntry(currentTime);
        
        if (floorEntry == null || ceilingEntry == null || 
            floorEntry.getKey().equals(ceilingEntry.getKey())) {
            // At exact time step or outside range
            return 0.0;
        }
        
        Duration betweenSteps = Duration.between(floorEntry.getKey(), ceilingEntry.getKey());
        Duration sinceFloor = Duration.between(floorEntry.getKey(), currentTime);
        
        double factor = (double) sinceFloor.toNanos() / betweenSteps.toNanos();
        
        // Cache result
        cacheInterpolationWeights(currentTime, floorEntry.getValue(), 
                                 ceilingEntry.getValue(), factor);
        
        return factor;
    }
    
    /**
     * Get interpolation weights for bilinear interpolation
     */
    public InterpolationWeights getInterpolationWeights() {
        LocalDateTime currentTime = getCurrentDateTime();
        
        // Check cache
        InterpolationWeights cached = interpolationCache.get(currentTime);
        if (cached != null) {
            return cached;
        }
        
        // Calculate weights
        Map.Entry<LocalDateTime, Integer> floorEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).floorEntry(currentTime);
        Map.Entry<LocalDateTime, Integer> ceilingEntry = 
            ((ConcurrentSkipListMap<LocalDateTime, Integer>) timeIndexMap).ceilingEntry(currentTime);
        
        if (floorEntry == null || ceilingEntry == null || 
            floorEntry.getKey().equals(ceilingEntry.getKey())) {
            // Exact match or edge case
            int index = floorEntry != null ? floorEntry.getValue() : 
                       ceilingEntry != null ? ceilingEntry.getValue() : 0;
            return new InterpolationWeights(index, index, 0.0, 1.0);
        }
        
        Duration betweenSteps = Duration.between(floorEntry.getKey(), ceilingEntry.getKey());
        Duration sinceFloor = Duration.between(floorEntry.getKey(), currentTime);
        
        double factor = (double) sinceFloor.toNanos() / betweenSteps.toNanos();
        double weight1 = 1.0 - factor;
        double weight2 = factor;
        
        InterpolationWeights weights = new InterpolationWeights(
            floorEntry.getValue(), ceilingEntry.getValue(), weight1, weight2);
        
        // Cache
        cacheInterpolationWeights(currentTime, weights);
        
        return weights;
    }
    
    private void cacheInterpolationWeights(LocalDateTime time, InterpolationWeights weights) {
        interpolationCache.put(time, weights);
        
        // Limit cache size
        if (interpolationCache.size() > interpolationCacheSize) {
            LocalDateTime oldest = interpolationCache.keySet().iterator().next();
            interpolationCache.remove(oldest);
        }
    }
    
    private void cacheInterpolationWeights(LocalDateTime time, int index1, int index2, double factor) {
        cacheInterpolationWeights(time, new InterpolationWeights(index1, index2, 1.0 - factor, factor));
    }
    
    private int clampToValidRange(int index) {
        if (netcdfTimes.isEmpty()) {
            return index;
        }
        return Math.max(0, Math.min(index, netcdfTimes.size() - 1));
    }
    
    /**
     * Get all available NetCDF time steps
     */
    public List<LocalDateTime> getNetCDFTimes() {
        return Collections.unmodifiableList(netcdfTimes);
    }
    
    /**
     * Check if current time has corresponding NetCDF data
     */
    public boolean hasNetCDFDataForCurrentTime() {
        if (!synchronizedWithNetCDF) return false;
        
        LocalDateTime currentTime = getCurrentDateTime();
        return !netcdfTimes.isEmpty() &&
               !currentTime.isBefore(netcdfTimes.get(0)) &&
               !currentTime.isAfter(netcdfTimes.get(netcdfTimes.size() - 1));
    }
    
    /**
     * Get temporal resolution of NetCDF data
     */
    public Duration getNetCDFTimeStep() {
        return netcdfTimeStep;
    }
    
    /**
     * Get synchronization status
     */
    public boolean isSynchronizedWithNetCDF() {
        return synchronizedWithNetCDF;
    }
    
    /**
     * Clear interpolation cache
     */
    public void clearCache() {
        interpolationCache.clear();
    }
    
    /**
     * Interpolation weights for temporal interpolation
     */
    public static class InterpolationWeights {
        public final int index1;  // Earlier time index
        public final int index2;  // Later time index
        public final double weight1;  // Weight for index1
        public final double weight2;  // Weight for index2
        public final double factor;   // Interpolation factor (0.0 to 1.0)
        
        public InterpolationWeights(int index1, int index2, double weight1, double weight2) {
            this.index1 = index1;
            this.index2 = index2;
            this.weight1 = weight1;
            this.weight2 = weight2;
            this.factor = weight2; // factor = weight of later time
        }
        
        /**
         * Interpolate between two values
         */
        public double interpolate(double value1, double value2) {
            return value1 * weight1 + value2 * weight2;
        }
        
        /**
         * Check if interpolation is needed (not at exact time step)
         */
        public boolean needsInterpolation() {
            return weight2 > 0.0 && weight2 < 1.0;
        }
        
        @Override
        public String toString() {
            return String.format("Interpolation[%d(%.3f) + %d(%.3f)]", 
                index1, weight1, index2, weight2);
        }
    }
}