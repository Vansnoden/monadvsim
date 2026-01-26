package com.monadvsim.app.models.utils;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

        // Generate timestamp for filename
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        if (outputFileName == null) {
            outputFileName = String.format("merged_snapshots_%s.csv", timestamp);
        }

        File resultsDir = new File(resultsDirPath);
        if (!resultsDir.exists() || !resultsDir.isDirectory()) {
            System.out.println("Results directory not found: " + resultsDirPath);
            return;
        }

        try {
            // Get all snapshot files sorted by tick number
            List<File> snapshotFiles = Arrays.stream(resultsDir.listFiles())
                .filter(file -> file.getName().startsWith("snapshot_tick_") && file.getName().endsWith(".csv"))
                .filter(file -> file.length() > 0) // Only include non-empty files
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

            // Create output file with timestamp
            File mergedFile = new File(resultsDir, outputFileName);

            try (PrintWriter writer = new PrintWriter(new FileWriter(mergedFile))) {
                boolean headerWritten = false;
                int totalRows = 0;

                for (int i = 0; i < snapshotFiles.size(); i++) {
                    File snapshotFile = snapshotFiles.get(i);

                    System.out.println("Processing: " + snapshotFile.getName() + 
                                     " (Size: " + snapshotFile.length() + " bytes)");

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

                            // Skip header for subsequent files (looks for "TickCount,AgentID")
                            if (i > 0 && line.startsWith("TickCount,AgentID")) {
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

                if (totalRows == 0) {
                    System.out.println("WARNING: No data rows were merged!");
                    // Check if we're missing data due to header issues
                    if (snapshotFiles.size() > 0) {
                        System.out.println("Checking first file for data...");
                        checkFileContents(snapshotFiles.get(0));
                    }
                } else {
                    System.out.printf("Successfully merged %d files into %s (total rows: %d)%n",
                        snapshotFiles.size(), mergedFile.getPath(), totalRows);

                    // Delete intermediate files only if merge was successful
                    deleteIntermediateFiles(snapshotFiles);
                }

            } catch (IOException e) {
                System.err.println("Error writing merged file: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Error merging snapshots: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void checkFileContents(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            int lineNum = 0;
            String line;
            while ((line = reader.readLine()) != null && lineNum < 5) {
                System.out.println("Line " + lineNum + ": " + line);
                lineNum++;
            }
        } catch (IOException e) {
            System.err.println("Error checking file contents: " + e.getMessage());
        }
    }

    // Update mergeAfterSimulation to use timestamp
    public static void mergeAfterSimulation() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        mergeSnapshotsAndCleanup("results", "merged_snapshots_" + timestamp + ".csv");
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