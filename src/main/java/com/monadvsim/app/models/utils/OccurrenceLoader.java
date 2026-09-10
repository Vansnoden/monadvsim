package com.monadvsim.app.models.utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Utility for loading occurrence data from CSV files.
 * Expected format:
 *   longitude, latitude, year, month, day, source
 * 
 * @author void
 */
public class OccurrenceLoader {
    
    public static class OccurrencePoint {
        public final double longitude;
        public final double latitude;
        public final int year;
        public final int month;
        public final int day;
        public final String source;
        public final LocalDate date;
        
        public OccurrencePoint(double longitude, double latitude, int year, int month, int day, String source) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.year = year;
            this.month = month;
            this.day = day;
            this.source = source;
            this.date = LocalDate.of(year, month, day);
        }
        
        @Override
        public String toString() {
            return String.format("Occurrence(%.6f, %.6f, %d-%02d-%02d, %s)", 
                longitude, latitude, year, month, day, source);
        }
    }
    
    /**
     * Load occurrence points from a CSV file.
     * 
     * Expected header: longitude,latitude,year,month,day,source
     * If month/day are missing, they default to 1.
     */
    public static List<OccurrencePoint> loadOccurrences(String filePath) throws IOException {
        List<OccurrencePoint> points = new ArrayList<>();
        String line;
        boolean headerSkipped = false;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                // Skip header
                if (!headerSkipped) {
                    headerSkipped = true;
                    if (line.toLowerCase().contains("longitude") && 
                        line.toLowerCase().contains("latitude")) {
                        continue;
                    }
                }
                
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    SimulationLogger.warning("Skipping malformed occurrence line: %s", line);
                    continue;
                }
                
                try {
                    double lon = Double.parseDouble(parts[0].trim());
                    double lat = Double.parseDouble(parts[1].trim());
                    
                    // Parse year
                    int year = Integer.parseInt(parts[2].trim());
                    int month = 1;
                    int day = 1;
                    String source = "";
                    
                    if (parts.length > 3) {
                        try {
                            month = Integer.parseInt(parts[3].trim());
                        } catch (NumberFormatException e) {
                            // Month may be text - try to parse date
                            try {
                                // Try to parse as date string
                                String dateStr = parts[3].trim();
                                // Try common formats
                                if (dateStr.contains("-")) {
                                    String[] dateParts = dateStr.split("-");
                                    if (dateParts.length >= 2) {
                                        month = Integer.parseInt(dateParts[1]);
                                        if (dateParts.length >= 3) {
                                            day = Integer.parseInt(dateParts[2]);
                                        }
                                    }
                                }
                            } catch (Exception e2) {
                                // Ignore - keep default
                            }
                        }
                    }
                    
                    if (parts.length > 4) {
                        try {
                            day = Integer.parseInt(parts[4].trim());
                        } catch (NumberFormatException e) {
                            // Ignore - keep default
                        }
                    }
                    
                    if (parts.length > 5) {
                        source = parts[5].trim();
                    }
                    
                    // Validate month/day
                    if (month < 1) month = 1;
                    if (month > 12) month = 12;
                    if (day < 1) day = 1;
                    if (day > 31) day = 31;
                    
                    points.add(new OccurrencePoint(lon, lat, year, month, day, source));
                    
                } catch (NumberFormatException e) {
                    SimulationLogger.warning("Skipping occurrence with invalid number: %s", line);
                }
            }
        }
        
        SimulationLogger.info("Loaded %d occurrence points from %s", points.size(), filePath);
        return points;
    }
    
    /**
     * Filter occurrence points by year range.
     */
    public static List<OccurrencePoint> filterByYear(List<OccurrencePoint> points, int yearStart, int yearEnd) {
        List<OccurrencePoint> filtered = new ArrayList<>();
        for (OccurrencePoint p : points) {
            if (p.year >= yearStart && p.year <= yearEnd) {
                filtered.add(p);
            }
        }
        SimulationLogger.info("Filtered to %d occurrence points in years %d-%d", 
            filtered.size(), yearStart, yearEnd);
        return filtered;
    }
    
    /**
     * Filter occurrence points by year range.
     */
    public static List<OccurrencePoint> filterByYear(List<OccurrencePoint> points, int year) {
        return filterByYear(points, year, year);
    }
    
    /**
     * Get summary statistics of occurrence points.
     */
    public static Map<String, Object> getSummary(List<OccurrencePoint> points) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", points.size());
        
        // Year distribution
        Map<Integer, Integer> yearCounts = new HashMap<>();
        for (OccurrencePoint p : points) {
            yearCounts.put(p.year, yearCounts.getOrDefault(p.year, 0) + 1);
        }
        summary.put("byYear", yearCounts);
        
        // Source distribution
        Map<String, Integer> sourceCounts = new HashMap<>();
        for (OccurrencePoint p : points) {
            String source = p.source.isEmpty() ? "unknown" : p.source;
            sourceCounts.put(source, sourceCounts.getOrDefault(source, 0) + 1);
        }
        summary.put("bySource", sourceCounts);
        
        // Spatial bounds
        if (!points.isEmpty()) {
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            for (OccurrencePoint p : points) {
                minLon = Math.min(minLon, p.longitude);
                maxLon = Math.max(maxLon, p.longitude);
                minLat = Math.min(minLat, p.latitude);
                maxLat = Math.max(maxLat, p.latitude);
            }
            summary.put("minLon", minLon);
            summary.put("maxLon", maxLon);
            summary.put("minLat", minLat);
            summary.put("maxLat", maxLat);
        }
        
        return summary;
    }
}