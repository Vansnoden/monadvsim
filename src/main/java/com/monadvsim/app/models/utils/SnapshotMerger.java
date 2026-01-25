package com.monadvsim.app.models.utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class SnapshotMerger {
    
    /**
     * Merges all snapshot CSV files in the results directory into a single CSV file
     * and deletes the intermediate files.
     * 
     * @param resultsDirPath Path to the results directory (default: "results")
     * @param outputFileName Name of the merged output file (default: "merged_snapshots.csv")
     */
    public static void mergeSnapshotsAndCleanup(String resultsDirPath, String outputFileName) {
        if (resultsDirPath == null) resultsDirPath = "results";
        if (outputFileName == null) outputFileName = "merged_snapshots.csv";
        
        File resultsDir = new File(resultsDirPath);
        if (!resultsDir.exists() || !resultsDir.isDirectory()) {
            System.out.println("Results directory not found: " + resultsDirPath);
            return;
        }
        
        try {
            // Get all snapshot files sorted by tick number
            List<File> snapshotFiles = Arrays.stream(resultsDir.listFiles())
                .filter(file -> file.getName().startsWith("snapshot_tick_") && file.getName().endsWith(".csv"))
                .sorted((f1, f2) -> {
                    int tick1 = extractTickNumber(f1.getName());
                    int tick2 = extractTickNumber(f2.getName());
                    return Integer.compare(tick1, tick2);
                })
                .collect(Collectors.toList());
            
            if (snapshotFiles.isEmpty()) {
                System.out.println("No snapshot files found to merge.");
                return;
            }
            
            System.out.println("Found " + snapshotFiles.size() + " snapshot files to merge.");
            
            // Create output file
            File mergedFile = new File(resultsDir, outputFileName);
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(mergedFile))) {
                boolean headerWritten = false;
                int totalRows = 0;
                
                for (int i = 0; i < snapshotFiles.size(); i++) {
                    File snapshotFile = snapshotFiles.get(i);
                    
                    try (BufferedReader reader = new BufferedReader(new FileReader(snapshotFile))) {
                        String line;
                        int rowCount = 0;
                        
                        while ((line = reader.readLine()) != null) {
                            // Skip empty lines
                            if (line.trim().isEmpty()) continue;
                            
                            // Write header only once (from first file)
                            if (i == 0 && !headerWritten) {
                                writer.println(line);
                                headerWritten = true;
                                continue;
                            }
                            
                            // Skip header for subsequent files
                            if (i > 0 && rowCount == 0) {
                                rowCount++;
                                continue;
                            }
                            
                            writer.println(line);
                            rowCount++;
                            totalRows++;
                        }
                        
                        System.out.printf("  Processed %s: %d rows%n", 
                            snapshotFile.getName(), rowCount);
                        
                    } catch (IOException e) {
                        System.err.println("Error reading file: " + snapshotFile.getName() + " - " + e.getMessage());
                    }
                }
                
                System.out.printf("Successfully merged %d files into %s (total rows: %d)%n",
                    snapshotFiles.size(), mergedFile.getPath(), totalRows);
                
                // Delete intermediate files
                deleteIntermediateFiles(snapshotFiles);
                
            } catch (IOException e) {
                System.err.println("Error writing merged file: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("Error merging snapshots: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Extracts the tick number from snapshot filename
     */
    private static int extractTickNumber(String filename) {
        try {
            // Extract number from "snapshot_tick_123.csv"
            String numberPart = filename.replace("snapshot_tick_", "").replace(".csv", "");
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException e) {
            return 0; // Default for sorting
        }
    }
    
    /**
     * Deletes the intermediate snapshot files
     */
    private static void deleteIntermediateFiles(List<File> snapshotFiles) {
        int deletedCount = 0;
        long totalSize = 0;
        
        for (File file : snapshotFiles) {
            try {
                totalSize += file.length();
                if (file.delete()) {
                    deletedCount++;
                } else {
                    System.err.println("Warning: Could not delete " + file.getName());
                }
            } catch (SecurityException e) {
                System.err.println("Security exception when deleting " + file.getName() + ": " + e.getMessage());
            }
        }
        
        System.out.printf("Deleted %d intermediate files (%.2f MB freed)%n",
            deletedCount, totalSize / (1024.0 * 1024.0));
    }
    
    /**
     * Alternative method that can be called from the simulation engine after completion
     */
    public static void mergeAfterSimulation() {
        mergeSnapshotsAndCleanup("results", "merged_snapshots.csv");
    }
    
    /**
     * Command-line interface for merging snapshots
     */
//    public static void main(String[] args) {
//        if (args.length == 0) {
//            // Default behavior: merge snapshots in results directory
//            mergeSnapshotsAndCleanup("results", "merged_snapshots.csv");
//        } else if (args.length == 1) {
//            mergeSnapshotsAndCleanup(args[0], "merged_snapshots.csv");
//        } else if (args.length == 2) {
//            mergeSnapshotsAndCleanup(args[0], args[1]);
//        } else {
//            System.out.println("Usage: java SnapshotMerger [results_directory] [output_filename]");
//            System.out.println("Example: java SnapshotMerger results combined_results.csv");
//        }
//    }
}