package com.monadvsim.app.models;

import com.monadvsim.app.models.RasterLayer;
import com.monadvsim.app.models.VectorLayer;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.geotools.api.feature.simple.SimpleFeature;

import java.io.File;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class EnvironmentService {
  
  public static double[] calculateGradient(RasterLayer layer, double x, double y, 
                                         File projectFile, double sampleDistance) {
    // Sample nearby points to calculate gradient
    double valueCenter = getValueOrDefault(layer, x, y, projectFile);
    double valueRight = getValueOrDefault(layer, x + sampleDistance, y, projectFile);
    double valueLeft = getValueOrDefault(layer, x - sampleDistance, y, projectFile);
    double valueUp = getValueOrDefault(layer, x, y + sampleDistance, projectFile);
    double valueDown = getValueOrDefault(layer, x, y - sampleDistance, projectFile);
    
    // Calculate partial derivatives
    double dx = (valueRight - valueLeft) / (2 * sampleDistance);
    double dy = (valueUp - valueDown) / (2 * sampleDistance);
    
    return new double[]{dx, dy};
  }
  
  private static double getValueOrDefault(RasterLayer layer, double x, double y, 
                                        File projectFile) {
    Double value = layer.getValueAt(x, y, projectFile);
    return value != null ? value : 0.0;
  }
  
  public static String detectTerrainAt(VectorLayer layer, double x, double y, 
                                     File projectFile) {
    try {
      GeometryFactory gf = new GeometryFactory();
      Point point = gf.createPoint(new Coordinate(x, y));
      SimpleFeature feature = layer.getFeatureAt(point, projectFile);
      
      if (feature != null) {
        Object type = feature.getAttribute("type");
        if (type != null) {
          return type.toString().toLowerCase();
        }
        return feature.getType().getName().getLocalPart().toLowerCase();
      }
    } catch (Exception e) {
      // Silently fail - terrain is "empty"
    }
    return "empty";
  }
  
  public static Map<String, Double> sampleEnvironmentAt(RasterLayer layer, double x, double y, 
                                                       File projectFile, double radius) {
    Map<String, Double> samples = new HashMap<>();
    
    // Sample at different distances and angles
    int samplesPerCircle = 8;
    for (int circle = 1; circle <= 3; circle++) {
      double currentRadius = radius * circle / 3;
      for (int i = 0; i < samplesPerCircle; i++) {
        double angle = 2 * Math.PI * i / samplesPerCircle;
        double sampleX = x + currentRadius * Math.cos(angle);
        double sampleY = y + currentRadius * Math.sin(angle);
        
        Double value = layer.getValueAt(sampleX, sampleY, projectFile);
        if (value != null) {
          String key = String.format("r%.1f_a%.0f", currentRadius, Math.toDegrees(angle));
          samples.put(key, value);
        }
      }
    }
    
    return samples;
  }
  
  public static double calculateGradientMagnitude(RasterLayer layer, double x, double y, 
                                                File projectFile, double sampleDistance) {
    double[] gradient = calculateGradient(layer, x, y, projectFile, sampleDistance);
    return Math.sqrt(gradient[0] * gradient[0] + gradient[1] * gradient[1]);
  }
  
  public static double[] calculateGradientDirection(RasterLayer layer, double x, double y, 
                                                  File projectFile, double sampleDistance) {
    double[] gradient = calculateGradient(layer, x, y, projectFile, sampleDistance);
    double magnitude = Math.sqrt(gradient[0] * gradient[0] + gradient[1] * gradient[1]);
    
    if (magnitude > 0) {
      return new double[]{gradient[0] / magnitude, gradient[1] / magnitude};
    }
    return new double[]{0, 0};
  }
  
  public static double getInterpolatedValue(RasterLayer layer, double x, double y, 
                                          File projectFile) {
    // Simple bilinear interpolation
    double x1 = Math.floor(x);
    double x2 = Math.ceil(x);
    double y1 = Math.floor(y);
    double y2 = Math.ceil(y);
    
    Double q11 = layer.getValueAt(x1, y1, projectFile);
    Double q12 = layer.getValueAt(x1, y2, projectFile);
    Double q21 = layer.getValueAt(x2, y1, projectFile);
    Double q22 = layer.getValueAt(x2, y2, projectFile);
    
    if (q11 == null || q12 == null || q21 == null || q22 == null) {
      return getValueOrDefault(layer, x, y, projectFile);
    }
    
    double xWeight = (x - x1) / (x2 - x1);
    double yWeight = (y - y1) / (y2 - y1);
    
    double r1 = q11 * (1 - xWeight) + q21 * xWeight;
    double r2 = q12 * (1 - xWeight) + q22 * xWeight;
    
    return r1 * (1 - yWeight) + r2 * yWeight;
  }
  
  public static List<String> detectNearbyTerrains(VectorLayer layer, double x, double y, 
                                                File projectFile, double radius) {
    List<String> terrains = new ArrayList<>();
    GeometryFactory gf = new GeometryFactory();
    
    // Check in a grid pattern within the radius
    int gridSize = 5;
    for (int i = -gridSize; i <= gridSize; i++) {
      for (int j = -gridSize; j <= gridSize; j++) {
        double sampleX = x + (i * radius / gridSize);
        double sampleY = y + (j * radius / gridSize);
        
        String terrain = detectTerrainAt(layer, sampleX, sampleY, projectFile);
        if (!"empty".equals(terrain) && !terrains.contains(terrain)) {
          terrains.add(terrain);
        }
      }
    }
    
    return terrains;
  }
  
  public static boolean isLocationValid(RasterLayer layer, double x, double y, 
                                      File projectFile, double minValue, double maxValue) {
    Double value = layer.getValueAt(x, y, projectFile);
    if (value == null) {
      return false;
    }
    return value >= minValue && value <= maxValue;
  }
  
  public static double calculateAverageInRadius(RasterLayer layer, double x, double y, 
                                              File projectFile, double radius, int samples) {
    double total = 0;
    int validSamples = 0;
    
    for (int i = 0; i < samples; i++) {
      double angle = 2 * Math.PI * i / samples;
      double r = radius * Math.sqrt(Math.random());
      double sampleX = x + r * Math.cos(angle);
      double sampleY = y + r * Math.sin(angle);
      
      Double value = layer.getValueAt(sampleX, sampleY, projectFile);
      if (value != null) {
        total += value;
        validSamples++;
      }
    }
    
    return validSamples > 0 ? total / validSamples : 0;
  }
  
  public static String getDominantTerrainInRadius(VectorLayer layer, double x, double y, 
                                                File projectFile, double radius) {
    Map<String, Integer> terrainCounts = new HashMap<>();
    GeometryFactory gf = new GeometryFactory();
    
    // Sample points in a circular pattern
    int samples = 12;
    for (int i = 0; i < samples; i++) {
      double angle = 2 * Math.PI * i / samples;
      double r = radius * Math.sqrt(Math.random());
      double sampleX = x + r * Math.cos(angle);
      double sampleY = y + r * Math.sin(angle);
      
      String terrain = detectTerrainAt(layer, sampleX, sampleY, projectFile);
      if (!"empty".equals(terrain)) {
        terrainCounts.put(terrain, terrainCounts.getOrDefault(terrain, 0) + 1);
      }
    }
    
    // Find dominant terrain
    String dominant = "empty";
    int maxCount = 0;
    for (Map.Entry<String, Integer> entry : terrainCounts.entrySet()) {
      if (entry.getValue() > maxCount) {
        maxCount = entry.getValue();
        dominant = entry.getKey();
      }
    }
    
    return dominant;
  }
}
