package com.monadvsim.app.models.engine;


import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ManagedExecutorService;
import com.monadvsim.app.models.utils.ResourceManager;
import com.monadvsim.app.models.utils.SnapshotMerger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * Core Simulation Loop
 *
 * Main simulation controller implementing Runnable
 *
 * Manages the simulation tick sequence: time advance → environment update →
 * agent processing
 *
 * Handles periodic snapshot exports and statistics reporting
 *
 * Integrates with TimeManager, SpatialRegistry, and AgentLifecycleManager
 *
 * Implements adaptive sleep for real-time simulation pacing
 *
 * Provides resource cleanup and monitoring
 * 
 * 
 * @author void
 */


public class SimulationEngine implements Runnable {
    
    private final Project project;
    private final TimeManager timeManager;
    private final SpatialRegistry spatialRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ReentrantLock simulationLock = new ReentrantLock();
    private final ResourceManager resourceManager = ResourceManager.getInstance();
    private final ManagedExecutorService simulationExecutor;
    private final AtomicBoolean resourcesCleaned = new AtomicBoolean(false);
    // Performance monitoring
    private final SimulationMetrics metrics = new SimulationMetrics();
    private long lastTickTime = 0;
    private double averageTickTime = 0;
    private final int tickTimeWindow = 100;
    // Statistics
    private final ConcurrentHashMap<String, AtomicInteger> layerStats = new ConcurrentHashMap<>();
    private final Object exportLock = new Object();
    // Export management
    private final ExecutorService exportExecutor;
    private final AtomicBoolean exportInProgress = new AtomicBoolean(false);
    private final Queue<ExportTask> exportQueue = new ConcurrentLinkedQueue<>();

    
    public SimulationEngine(Project project, TimeManager timeManager, SpatialRegistry spatialRegistry) {
        this.project = project;
        this.timeManager = timeManager;
        this.spatialRegistry = spatialRegistry;
        this.project.setSpatialRegistry(spatialRegistry);
        
        // Create managed executor service
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.simulationExecutor = new ManagedExecutorService(
            "SimulationEngine",
            Math.max(2, coreCount - 1),  // Leave one core for system
            coreCount * 2,               // Maximum threads
            1000,                        // Queue capacity
            60, TimeUnit.SECONDS         // Keep-alive time
        );
        
        this.exportExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Export-Thread");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        // Register shutdown hook
        this.simulationExecutor.addShutdownHook(this::cleanupResources);
    }
    
    
    @Override
    public void run() {
        running.set(true); 
        System.out.println("🚀Simulation Engine with Incremental Updates Started...");

        try {
            while (running.get()) {
                long tickStart = System.nanoTime();

                // TICK SEQUENCE:
                // 1. Advance time
                if (!timeManager.tick()) {
                    System.out.println("Simulation time limit reached at tick "
                            + timeManager.getTickCount());
                    break;
                }
                
//                if (!areAnyLivingAgentsAlive()) {
//                    System.out.println("🛑 All living agents have died! Stopping simulation at tick " 
//                            + timeManager.getTickCount());
//                    System.out.println("Total ticks completed: " + timeManager.getTickCount());
//                    running.set(false);
//                    break;
//                }

                // 2. Update environment
                project.updateEnvironment(timeManager.getCurrentFrameIndex());

                // 3. Process agent layers WITH immediate spatial updates
                processAgentLayersWithImmediateUpdates();

                // 4. Finalize spatial registry for this tick
                finalizeSpatialRegistry();

                // 5. Report progress and export periodic snapshots
                reportTickProgress(tickStart);
                
                // Adaptive sleep
                adaptiveSleep(System.nanoTime() - tickStart);
            }
        } catch (Exception e) {
            System.err.println("Error in simulation loop: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Always run cleanup
            cleanup();
        }
    }
    
    
    private void finalizeSpatialRegistry() {
        // Apply any pending changes in spatial registry
        SpatialRegistry registry = 
            (SpatialRegistry) project.getSpatialRegistry();
        registry.applyPendingChanges();
        
        // Validate consistency (debug builds only)
        if (System.getProperty("debug.spatial") != null) {
            validateSpatialConsistency();
        }
    }
    
    
    private boolean areAnyLivingAgentsAlive() {
        // Check all agent layers for living agents
        for (AgentLayer layer : project.getAgentLayers()) {
            List<Agent> agents = layer.getAgents();
            for (Agent agent : agents) {
                if (agent instanceof LivingAgent la) {
                    if (la.isAlive()) {
                        return true; // Found at least one living agent
                    }
                }
            }
        }
        return false; // No living agents found
    }
    
    private int getLivingAgentCount() {
        int count = 0;
        for (AgentLayer layer : project.getAgentLayers()) {
            List<Agent> agents = layer.getAgents();
            for (Agent agent : agents) {
                if (agent instanceof LivingAgent la && la.isAlive()) {
                    count++;
                }
            }
        }
        return count;
    }
    
    
    private void validateSpatialConsistency() {
        SpatialRegistry registry = 
            (SpatialRegistry) project.getSpatialRegistry();
        
        // Get all agents from layers
        List<Agent> layerAgents = project.getAgentLayers().stream()
            .flatMap(l -> l.getAgents().stream())
            .collect(Collectors.toList());
        
        // Get all agents from spatial registry
        List<Agent> registryAgents = registry.getAllAgents();
        
        // Check counts match
        if (layerAgents.size() != registryAgents.size()) {
            System.err.printf("Spatial inconsistency: layers=%d, registry=%d%n",
                layerAgents.size(), registryAgents.size());
            
            // Find missing agents
            Set<String> layerIds = layerAgents.stream()
                .map(Agent::getId)
                .collect(Collectors.toSet());
            Set<String> registryIds = registryAgents.stream()
                .map(Agent::getId)
                .collect(Collectors.toSet());
            
            Set<String> missingInRegistry = new HashSet<>(layerIds);
            missingInRegistry.removeAll(registryIds);
            
            Set<String> extraInRegistry = new HashSet<>(registryIds);
            extraInRegistry.removeAll(layerIds);
            
            if (!missingInRegistry.isEmpty()) {
                System.err.println("Agents missing in registry: " + missingInRegistry.size());
            }
            if (!extraInRegistry.isEmpty()) {
                System.err.println("Extra agents in registry: " + extraInRegistry.size());
            }
        }
    }
    
    
    private void reportTickProgress(long tickStart) {
        long tickDuration = System.nanoTime() - tickStart;

        // Update performance metrics
        updatePerformanceMetrics(tickDuration);

        // Export snapshot every 100 ticks
        if (timeManager.getTickCount() % 100 == 0) {
            exportPeriodicSnapshot();
            printExportStatus();
        }

        // Print progress every 10 ticks
        if (timeManager.getTickCount() % 10 == 0) {
            reportProgress();
        }
    }
    
    
    private void exportPeriodicSnapshot() {
        long currentTick = timeManager.getTickCount();

        // Check for stuck exports
        checkAndClearStuckExports();

        System.out.printf("[Export] Scheduling export for tick %d at %s%n", 
            currentTick, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        ExportTask task = new ExportTask(project, currentTick);
        exportQueue.offer(task);

        System.out.printf("[Export] Queue size: %d%n", exportQueue.size());

        if (exportInProgress.compareAndSet(false, true)) {
            System.out.println("[Export] Starting export processing...");
            exportExecutor.submit(() -> {
                try {
                    processExportQueue();
                } catch (Exception e) {
                    System.err.println("[Export] Error in export processing: " + e.getMessage());
                    e.printStackTrace();
                    exportInProgress.set(false);
                }
            });
        }
    }
    
    
    private void exportSnapshot(Project project) {
        long currentTick = timeManager.getTickCount();

        // Create a snapshot of project data for export (avoids concurrency issues)
        ExportTask task = new ExportTask(project, currentTick);
        exportQueue.offer(task);

        // Process export queue if not already processing
        if (exportInProgress.compareAndSet(false, true)) {
            exportExecutor.submit(this::processExportQueue);
        }
    }
    
    
    private void processExportQueue() {
        try {
            while (!exportQueue.isEmpty()) {
                ExportTask task = exportQueue.poll();
                if (task != null) {
                    processSingleExport(task);
                }
            }
        } finally {
            exportInProgress.set(false);

            // If new tasks arrived while processing, start again
            if (!exportQueue.isEmpty() && exportInProgress.compareAndSet(false, true)) {
                exportExecutor.submit(this::processExportQueue);
            }
        }
    }
    
    
    private void processSingleExport(ExportTask task) {
        if (task == null || task.project == null) {
            System.err.println("[Export] ERROR: Invalid export task");
            return;
        }

        System.out.printf("[Export] START processing tick %d at %s%n", 
            task.tick, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        String filename = String.format("results/snapshot_tick_%d.csv", task.tick);

        // Use simplified export method
        ProjectPersistenceService persistenceService = new ProjectPersistenceService();
        boolean success;
        try {
            success = persistenceService.exportToCSV(task.project, filename, task.tick);
            if (success) {
                System.out.printf("[Export] DONE processing tick %d%n", task.tick);
            } else {
                System.err.printf("[Export] FAILED processing tick %d%n", task.tick);
            }
        } catch (IOException ex) {
            Logger.getLogger(SimulationEngine.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    
    private void checkAndClearStuckExports() {
        if (exportQueue.size() > 10) { // Too many queued exports
            System.err.println("[Export] WARNING: Too many queued exports (" + 
                              exportQueue.size() + "), clearing queue");
            exportQueue.clear();
            exportInProgress.set(false);
        }
    }
    
    
    private Project createCompleteProjectSnapshot(Project original, List<Agent> agents) {
        // Create a complete snapshot for export
        Project snapshot = new Project(original.getName() + "_snapshot_tick_" + System.currentTimeMillis());

        // Copy basic settings
        snapshot.setDefaultAgentSearchRadius(original.getDefaultAgentSearchRadius());
        snapshot.setDefaultHatchingProbability(original.getDefaultHatchingProbability());
        snapshot.setDefaultAgentStep(original.getDefaultAgentStep());
        snapshot.setDefaultBirthRate(original.getDefaultBirthRate());
        snapshot.setDefaultMaxAgentAge(original.getDefaultMaxAgentAge());

        // Copy ALL layers (both raster and agent layers)
        for (Layer layer : original.getLayers()) {
            if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) {
                // For raster layers, create a copy with current frame data
                snapshot.addLayer(layer);
            }
        }

        // Group agents by their original layer for proper export
        Map<String, List<Agent>> agentsByLayerName = new HashMap<>();
        for (Agent agent : agents) {
            // Find which layer this agent belongs to
            for (AgentLayer originalLayer : original.getAgentLayers()) {
                if (originalLayer.getAgents().contains(agent)) {
                    agentsByLayerName.computeIfAbsent(originalLayer.getName(), 
                        k -> new ArrayList<>()).add(agent);
                    break;
                }
            }
        }

        // Create agent layers with their respective agents
        for (Map.Entry<String, List<Agent>> entry : agentsByLayerName.entrySet()) {
            String layerName = entry.getKey();
            AgentLayer layerSnapshot = new AgentLayer(layerName, null, null);
            layerSnapshot.addAgents(entry.getValue());
            snapshot.addLayer(layerSnapshot);
        }

        // Copy spatial registry reference
        snapshot.setSpatialRegistry(original.getSpatialRegistry());

        return snapshot;
    }
    
    
    private Project createProjectSnapshot(Project original, List<Agent> agents) {
        // Create a lightweight snapshot for export
        Project snapshot = new Project(original.getName() + "_snapshot");

        // Copy basic settings
        snapshot.setDefaultAgentSearchRadius(original.getDefaultAgentSearchRadius());
        snapshot.setDefaultHatchingProbability(original.getDefaultHatchingProbability());
        snapshot.setDefaultAgentStep(original.getDefaultAgentStep());
        snapshot.setDefaultBirthRate(original.getDefaultBirthRate());
        snapshot.setDefaultMaxAgentAge(original.getDefaultMaxAgentAge());

        // Copy layers (these are mostly immutable)
        for (Layer layer : original.getLayers()) {
            if (!(layer instanceof AgentLayer)) {
                snapshot.addLayer(layer);
            }
        }

        // Create agent layers snapshot
        for (AgentLayer originalLayer : original.getAgentLayers()) {
            AgentLayer layerSnapshot = new AgentLayer(originalLayer.getName(), null, null);

            // Add only agents from this layer
            for (Agent agent : agents) {
                // This is simplified - you might need to filter by layer
                layerSnapshot.addAgent(agent);
            }

            snapshot.addLayer(layerSnapshot);
        }

        return snapshot;
    }
    
    private static class ExportTask {
        final Project project;
        final long tick;

        ExportTask(Project project, long tick) {
            this.project = project;
            this.tick = tick;
        }
    }
    
    
    private void reportSpatialStatistics() {
        SpatialRegistry registry = 
            (SpatialRegistry) project.getSpatialRegistry();
        
        Map<String, Object> stats = registry.getStatistics();
        System.out.printf("[Spatial] Agents: %d | Queries: %d | Cache: %d/%d%n",
            stats.get("totalAgents"), stats.get("queryCount"),
            stats.get("cacheSize"), stats.get("gridCells"));
    }
    
    
    private void cleanup() {
        System.out.println("Cleaning up simulation resources...");

        // 1. Ensure all pending exports are processed
        exportFinalSnapshotNow();

        // 2. Wait for any ongoing exports to complete
        waitForExportsToComplete();

        // 3. Now merge the snapshots
        mergeSnapshots();

        System.out.println("Cleanup completed successfully");
    }

    private void exportFinalSnapshotNow() {
        long currentTick = timeManager.getTickCount();
        try {
            String dirPath = "results";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String filename = String.format("results/snapshot_tick_%d.csv", currentTick);
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();

            // Get a safe copy of all agents
            List<Agent> agentsSnapshot = new ArrayList<>();
            for (AgentLayer layer : project.getAgentLayers()) {
                synchronized (layer) {
                    agentsSnapshot.addAll(new ArrayList<>(layer.getAgents()));
                }
            }

            // Create a complete project snapshot
            Project snapshot = createCompleteProjectSnapshot(project, agentsSnapshot);
            persistenceService.exportToCSV(snapshot, filename, currentTick);
            System.out.println("✅ FINAL Snapshot exported to: " + filename);

        } catch (Exception e) {
            System.err.println("❌ Error exporting final snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void waitForExportsToComplete() {
        if (exportExecutor != null) {
            exportExecutor.shutdown();
            try {
                System.out.println("Waiting for exports to complete...");
                if (!exportExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("Export timeout reached, forcing shutdown");
                    exportExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                exportExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    
    
    private void mergeSnapshots() {
        System.out.println("Merging snapshot files...");
        try {
            // Use your SnapshotMerger utility
            SnapshotMerger.mergeAfterSimulation();
        } catch (Exception e) {
            System.err.println("Failed to merge snapshots: " + e.getMessage());
        }
    }
    
    
    private void exportFinalSnapshot() {
        long currentTick = timeManager.getTickCount();
        try {
            String dirPath = "results";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String filename = String.format("results/snapshot_tick_%d.csv", currentTick);
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();

            // Get a safe copy of all agents
            List<Agent> agentsSnapshot = new ArrayList<>();
            for (AgentLayer layer : project.getAgentLayers()) {
                synchronized (layer) {
                    agentsSnapshot.addAll(new ArrayList<>(layer.getAgents()));
                }
            }

            // Create a complete project snapshot
            Project snapshot = createCompleteProjectSnapshot(project, agentsSnapshot);
            persistenceService.exportToCSV(snapshot, filename, currentTick);
            System.out.println("✅ FINAL Snapshot exported to: " + filename);

        } catch (Exception e) {
            System.err.println("❌ Error exporting final snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void mergeAllSnapshots() {
        try {
            System.out.println("🔄 Merging all snapshots...");

            // Create results directory if it doesn't exist
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
                System.out.println("⚠️ No results directory found, nothing to merge");
                return;
            }

            // Get all snapshot files
            File[] snapshotFiles = resultsDir.listFiles((dir, name) -> 
                name.startsWith("snapshot_tick_") && name.endsWith(".csv"));

            if (snapshotFiles == null || snapshotFiles.length == 0) {
                System.out.println("⚠️ No snapshot files found to merge");
                return;
            }

            System.out.println("Found " + snapshotFiles.length + " snapshot files to merge");

            // Sort files by tick number
            Arrays.sort(snapshotFiles, (f1, f2) -> {
                try {
                    int tick1 = extractTickNumber(f1.getName());
                    int tick2 = extractTickNumber(f2.getName());
                    return Integer.compare(tick1, tick2);
                } catch (Exception e) {
                    return 0;
                }
            });

            // Create merged filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String mergedFilename = String.format("results/merged_simulation_results_%s.csv", timestamp);

            // Merge all files
            try (PrintWriter writer = new PrintWriter(new FileWriter(mergedFilename))) {
                boolean headerWritten = false;
                int totalRows = 0;

                for (int i = 0; i < snapshotFiles.length; i++) {
                    File file = snapshotFiles[i];

                    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                        String line;
                        int lineNumber = 0;

                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;

                            // Write header only once (from first file)
                            if (i == 0 && !headerWritten && line.startsWith("TickCount,AgentID")) {
                                writer.println(line);
                                headerWritten = true;
                                continue;
                            }

                            // Skip header for subsequent files
                            if (i > 0 && lineNumber == 0 && line.startsWith("TickCount,AgentID")) {
                                lineNumber++;
                                continue;
                            }

                            writer.println(line);
                            totalRows++;
                            lineNumber++;
                        }

                        System.out.printf("Processed: %s (%d rows)%n", file.getName(), lineNumber);

                    } catch (IOException e) {
                        System.err.println("Error reading file: " + file.getName() + " - " + e.getMessage());
                    }
                }

                System.out.printf("Successfully merged %d files into %s (total rows: %d)%n",
                    snapshotFiles.length, mergedFilename, totalRows);

            } catch (IOException e) {
                System.err.println("Error writing merged file: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Error merging snapshots: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int extractTickNumber(String filename) {
        // Extract number from "snapshot_tick_123.csv"
        try {
            String numberPart = filename.replace("snapshot_tick_", "").replace(".csv", "");
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    
    private void exportSnapshotSynchronously(Project project) {
        long currentTick = timeManager.getTickCount();
        try {
            String dirPath = "results";
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String filename = String.format("results/snapshot_tick_%d.csv", currentTick);
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();

            // Get a safe copy of all agents
            List<Agent> agentsSnapshot = new ArrayList<>();
            for (AgentLayer layer : project.getAgentLayers()) {
                // Use getAllAgents() from AgentContainer which creates a copy
                agentsSnapshot.addAll(layer.getAgents());
            }

            // Create a complete project snapshot
            Project snapshot = createCompleteProjectSnapshot(project, agentsSnapshot);
            persistenceService.exportToCSV(snapshot, filename, currentTick);
            System.out.println("FINAL Snapshot exported to: " + filename);

        } catch (Exception e) {
            System.err.println("Error exporting final snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    private void processAgentLayersWithImmediateUpdates() {
        List<AgentLayer> layers = project.getAgentLayers();

        // Calculate adaptive timeout based on agent count
        long adaptiveTimeout = calculateAdaptiveTimeout(layers);

        for (AgentLayer layer : layers) {
            try {
                long layerStartTime = System.currentTimeMillis();

                // Process layer with progress monitoring
                CompletableFuture<Void> layerFuture = CompletableFuture.runAsync(() -> {
                    layer.update(project);
                });

                try {
                    // Wait with timeout, but allow more time for large layers
                    layerFuture.get(adaptiveTimeout, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    System.err.println("WARNING: Layer " + layer.getName() + 
                                     " processing timed out after " + adaptiveTimeout + "ms");
                    layerFuture.cancel(true);
                    // Continue with next layer instead of breaking
                }

            } catch (Exception e) {
                System.err.println("Error in layer " + layer.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    
    private long calculateAdaptiveTimeout(List<AgentLayer> layers) {
        int totalAgents = layers.stream()
            .mapToInt(l -> l.getAgents().size())
            .sum();

        // Base timeout + additional time per 1000 agents
        long baseTimeout = 5000; // 5 seconds base
        long perAgentTimeout = 10; // 10ms per 1000 agents

        return baseTimeout + (totalAgents / 1000) * perAgentTimeout;
    }

    
    
    private void printFinalStatistics() {
        try {
            // Create results directory
            File resultsDir = new File("results");
            if (!resultsDir.exists()) {
                resultsDir.mkdirs();
            }

            // Generate filename
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String safeProjectName = project.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = String.format("results/%s_final_stats_%s.txt", safeProjectName, timestamp);

            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                // Redirect console output to both console and file
                ConsoleAndFileWriter dualWriter = new ConsoleAndFileWriter(writer);

                dualWriter.println("\n=== Final Simulation Statistics ===");

                // Agent statistics
                int totalAgents = project.getAgentLayers().stream()
                    .mapToInt(l -> l.getAgents().size())
                    .sum();
                dualWriter.printf("Total agents: %,d%n", totalAgents);

                // Spatial registry statistics
                SpatialRegistry registry = project.getSpatialRegistry();
                Map<String, Object> stats = registry.getStatistics();

                dualWriter.printf("Spatial registry: %,d inserts, %,d removes, %,d updates%n",
                    stats.get("totalInserts"), stats.get("totalRemoves"), stats.get("totalUpdates"));

                // Layer statistics
                for (AgentLayer layer : project.getAgentLayers()) {
                    Map<String, Object> layerStats = layer.getStatistics();
                    dualWriter.printf("%s: %,d agents%n", 
                        layer.getName(), layerStats.get("agentCount"));
                }

                // Performance metrics
                dualWriter.printf("Average tick time: %.2f ms%n", averageTickTime);
                dualWriter.printf("Total ticks: %d%n", timeManager.getTickCount());

                System.out.println("✅ Statistics saved to: " + filename);

            } catch (IOException e) {
                System.err.println("Error writing statistics file: " + e.getMessage());
                // Fallback to console only
                printToConsoleOnly();
            }

        } catch (Exception e) {
            System.err.println("Error in statistics generation: " + e.getMessage());
            printToConsoleOnly();
        }
        
    }
    
    
    private void printToConsoleOnly() {
        System.out.println("\n=== Final Simulation Statistics ===");
        
        // Agent statistics
        int totalAgents = project.getAgentLayers().stream()
            .mapToInt(l -> l.getAgents().size())
            .sum();
        System.out.printf("Total agents: %,d%n", totalAgents);
        
        // Spatial registry statistics
        SpatialRegistry registry = project.getSpatialRegistry();
        Map<String, Object> stats = registry.getStatistics();

        System.out.printf("Spatial registry: %,d inserts, %,d removes, %,d updates%n",
            stats.get("totalInserts"), stats.get("totalRemoves"), stats.get("totalUpdates"));
        
        // Layer statistics
        for (AgentLayer layer : project.getAgentLayers()) {
            Map<String, Object> layerStats = layer.getStatistics();
            System.out.printf("%s: %,d agents%n", 
                layer.getName(), layerStats.get("agentCount"));
        }
    }
    
    
    // Helper class to write to both console and file
    private static class ConsoleAndFileWriter {
        private final PrintWriter fileWriter;

        ConsoleAndFileWriter(PrintWriter fileWriter) {
            this.fileWriter = fileWriter;
        }

        void println(String text) {
            System.out.println(text);
            fileWriter.println(text);
        }

        void printf(String format, Object... args) {
            String text = String.format(format, args);
            System.out.print(text);
            fileWriter.print(text);
        }
    }

    
    
    // Clean up all resources
    private void cleanupResources() {
        if (resourcesCleaned.compareAndSet(false, true)) {
            System.out.println("\n=== Cleaning up simulation resources ===");
            
            // Shutdown executor service
            if (simulationExecutor != null && !simulationExecutor.isTerminated()) {
                try {
                    System.out.println("Shutting down executor service...");
                    boolean terminated = simulationExecutor.gracefulShutdown(30, TimeUnit.SECONDS);
                    System.out.println("Executor shutdown: " + (terminated ? "successful" : "timed out"));
                    
                    // Print executor statistics
                    System.out.println("Executor statistics: " + simulationExecutor.getStatistics());
                    
                } catch (InterruptedException e) {
                    System.err.println("Interrupted during executor shutdown");
                    Thread.currentThread().interrupt();
                    simulationExecutor.shutdownNow();
                }
            }
            
            // Clean up agent layers
            if (project != null) {
                System.out.println("Cleaning up agent layers...");
                project.getAgentLayers().forEach(layer -> {
                    AgentLayer al = (AgentLayer) layer; 
                    al.shutdown();
                });
            }
            
            // Clean up spatial registry
            if (spatialRegistry != null) {
                System.out.println("Cleaning up spatial registry...");
                // Add any spatial registry cleanup if needed
            }
            
            // 4. Clean up raster layers
            if (project != null) {
                System.out.println("Cleaning up raster layers...");
                project.getLayers().forEach(layer -> {
                    if (layer instanceof MemoryMappedRasterLayer mmrl) {
                        mmrl.dispose();
                    }
                });
            }
            
            // 5. Force garbage collection
            System.out.println("Requesting garbage collection...");
            System.gc();
            System.runFinalization();
            
            // 6. Print final resource statistics
            System.out.println("\n=== Final Resource Statistics ===");
            Map<String, Object> stats = resourceManager.getStatistics();
            stats.forEach((key, value) -> System.out.printf("%s: %s%n", key, value));
            
            System.out.println("=== Resource cleanup complete ===");
        }
    }
    
    
    // Get engine statistics including resource usage
    public Map<String, Object> getDetailedStatistics() {
        Map<String, Object> stats = getState(); // From previous implementation
        
        // Add resource statistics
        stats.put("resourceManager", resourceManager.getStatistics());
        stats.put("executorService", simulationExecutor.getStatistics());
        
        // Add memory statistics
        Runtime runtime = Runtime.getRuntime();
        stats.put("memoryUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0));
        stats.put("memoryTotalMB", runtime.totalMemory() / (1024.0 * 1024.0));
        stats.put("memoryMaxMB", runtime.maxMemory() / (1024.0 * 1024.0));
        
        // Add file descriptor statistics (if available)
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() 
                    instanceof com.sun.management.UnixOperatingSystemMXBean) {
                com.sun.management.UnixOperatingSystemMXBean unixBean = 
                    (com.sun.management.UnixOperatingSystemMXBean) 
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                stats.put("openFileDescriptors", unixBean.getOpenFileDescriptorCount());
                stats.put("maxFileDescriptors", unixBean.getMaxFileDescriptorCount());
            }
        } catch (Exception e) {
            // Not available on this JVM
        }
        
        return stats;
    }
    
    
    public void stop() {
        running.set(false);
        System.out.println("Stopping simulation with resource cleanup...");
        cleanupResources();
    }
    
    
    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>();
        try {
            state.put("running", running.get());
            state.put("paused", paused.get());
            state.put("tick", timeManager.getTickCount());
            state.put("totalAgents", getTotalAgentCount());
            state.put("avgTickTime", averageTickTime);
            // Add timestamp
            state.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            // If we can't get the state, return minimal info
            state.put("error", "Could not get state: " + e.getMessage());
            state.put("timestamp", System.currentTimeMillis());
        }
        return state;
    }
    
    
    private int getTotalAgentCount() {
        return project.getAgentLayers().stream()
            .mapToInt(l -> l.getAgents().size())
            .sum();
    }
    
    
//    private void reportProgress() {
//        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
//            .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
//            .findFirst().orElse(null);
//
//        long adults = (mosquitoLayer != null) ? mosquitoLayer.getAgents().size() : 0;
//        
//        long totalLarvae = project.getAgentLayers().stream()
//            .flatMap(l -> l.getAgents().stream())
//            .filter(a -> a instanceof InertAgent)
//            .mapToLong(a -> ((InertAgent) a).getLarvalCount())
//            .sum();
//
//        System.out.println(String.format("Tick: %d | Adults: %d | Larvae: %d | Memory: %.1f MB", 
//            timeManager.getTickCount(), adults, totalLarvae,
//            Runtime.getRuntime().totalMemory() / (1024.0 * 1024.0)));
//    }
    
    private void reportProgress() {
        // Find the Mosquitoes layer
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
            .findFirst().orElse(null);

        long adults = 0;
        long pupae = 0;
        if (mosquitoLayer != null) {
            for (Agent agent : mosquitoLayer.getAgents()) {
                if (agent instanceof LivingAgent la && la.isAlive()) {
                    LifecycleStage stage = la.getStage();
                    if (stage == LifecycleStage.ADULT) {
                        adults++;
                    } else if (stage == LifecycleStage.PUPA) {
                        pupae++;
                    }
                }
            }
        }

        long totalLarvae = 0;
        long totalEggs = 0;
        // Sum over all InertAgent (water tanks) across all layers
        for (AgentLayer layer : project.getAgentLayers()) {
            for (Agent agent : layer.getAgents()) {
                if (agent instanceof InertAgent ia) {
                    totalLarvae += ia.getLarvalCount();
                    totalEggs += ia.getEggCount();
                }
            }
        }

        System.out.println(String.format("Tick: %d | Adults: %d | Pupae: %d | Larvae: %d | Eggs: %d | Memory: %.1f MB",
            timeManager.getTickCount(), adults, pupae, totalLarvae, totalEggs,
            Runtime.getRuntime().totalMemory() / (1024.0 * 1024.0)));
    }
    
    
    private void adaptiveSleep(long tickDuration) {
        long targetTickTime = 16_666_667L; // 60 FPS ≈ 16.67ms per tick
        
        if (tickDuration < targetTickTime) {
            long sleepTime = targetTickTime - tickDuration;
            try {
                TimeUnit.NANOSECONDS.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    
    private void logPerformance() {
        double fps = 1000.0 / averageTickTime;
        
        System.out.printf("[Perf] Tick %d: %.2f ms/tick (avg) | %.1f FPS | Agents: %d%n",
            timeManager.getTickCount(), averageTickTime, fps, getTotalAgentCount());
        
        // Log layer statistics
        layerStats.forEach((name, count) -> {
            System.out.printf("  %s: %,d agents%n", name, count.get());
        });
    }
    
    
    private void updatePerformanceMetrics(long tickDuration) {
        lastTickTime = tickDuration;
        
        // Calculate moving average
        if (averageTickTime == 0) {
            averageTickTime = tickDuration / 1_000_000.0; // Convert to ms
        } else {
            double alpha = 2.0 / (tickTimeWindow + 1);
            averageTickTime = (1 - alpha) * averageTickTime + 
                             alpha * (tickDuration / 1_000_000.0);
        }
    }
    
    
    private void printExportStatus() {
        System.out.printf("[Export Status] Queue: %d, In Progress: %b, Executor Active: %b, Shutdown: %b%n",
            exportQueue.size(), exportInProgress.get(), 
            exportExecutor != null && !exportExecutor.isShutdown(),
            exportExecutor != null && exportExecutor.isShutdown());

        // Check if results directory exists and is writable
        File resultsDir = new File("results");
        System.out.printf("[Export Status] Results dir exists: %b, writable: %b%n",
            resultsDir.exists(), resultsDir.canWrite());

        if (resultsDir.exists()) {
            File[] snapshotFiles = resultsDir.listFiles((dir, name) -> 
                name.startsWith("snapshot_tick_") && name.endsWith(".csv"));
            System.out.printf("[Export Status] Existing snapshots: %d%n", 
                snapshotFiles != null ? snapshotFiles.length : 0);
        }
    }
    
}