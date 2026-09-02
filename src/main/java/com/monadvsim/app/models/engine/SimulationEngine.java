package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.services.ProjectPersistenceService;
import com.monadvsim.app.models.utils.ManagedExecutorService;
import com.monadvsim.app.models.utils.ResourceManager;
import com.monadvsim.app.models.utils.SnapshotMerger;
import com.monadvsim.app.models.utils.SimulationLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Core Simulation Loop
 */
public class SimulationEngine implements Runnable {
    
    private final Project project;
    private final TimeManager timeManager;
    private final SpatialRegistry spatialRegistry;
    private final String outputDir;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ReentrantLock simulationLock = new ReentrantLock();
    private final ResourceManager resourceManager = ResourceManager.getInstance();
    private final ManagedExecutorService simulationExecutor;
    private final AtomicBoolean resourcesCleaned = new AtomicBoolean(false);

    private final SimulationMetrics metrics = new SimulationMetrics();
    private long lastTickTime = 0;
    private double averageTickTime = 0;
    private final int tickTimeWindow = 100;

    private final ConcurrentHashMap<String, AtomicInteger> layerStats = new ConcurrentHashMap<>();
    private final Object exportLock = new Object();

    private final ExecutorService exportExecutor;
    private final AtomicBoolean exportInProgress = new AtomicBoolean(false);
    private final Queue<ExportTask> exportQueue = new ConcurrentLinkedQueue<>();

    public SimulationEngine(Project project, TimeManager timeManager, SpatialRegistry spatialRegistry) {
        this(project, timeManager, spatialRegistry, "results");
    }
    
    public SimulationEngine(Project project, TimeManager timeManager, 
                           SpatialRegistry spatialRegistry, String outputDir) {
        this.project = project;
        this.timeManager = timeManager;
        this.spatialRegistry = spatialRegistry;
        this.outputDir = outputDir != null ? outputDir : "results";
        this.project.setSpatialRegistry(spatialRegistry);
        
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.simulationExecutor = new ManagedExecutorService(
            "SimulationEngine",
            Math.max(2, coreCount - 1),
            coreCount * 2,
            1000,
            60, TimeUnit.SECONDS
        );
        
        this.exportExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Export-Thread");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        this.simulationExecutor.addShutdownHook(this::cleanupResources);
        ensureOutputDirectory();
    }
    
    private void ensureOutputDirectory() {
        File dir = new File(outputDir);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                SimulationLogger.info("Created output directory: %s", outputDir);
            } else {
                SimulationLogger.warning("Could not create output directory: %s", outputDir);
            }
        }
    }
    
    @Override
    public void run() {
        running.set(true); 
        SimulationLogger.info("🚀 Simulation Engine Started. Output directory: %s", outputDir);

        try {
            while (running.get()) {
                long tickStart = System.nanoTime();

                if (!timeManager.tick()) {
                    SimulationLogger.info("Simulation time limit reached at tick " + timeManager.getTickCount());
                    break;
                }

                project.updateEnvironment(timeManager.getCurrentFrameIndex());
                processAgentLayersWithImmediateUpdates();
                finalizeSpatialRegistry();
                reportTickProgress(tickStart);
                adaptiveSleep(System.nanoTime() - tickStart);
            }
        } catch (Exception e) {
            SimulationLogger.warning("Error in simulation loop: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }
    
    private void finalizeSpatialRegistry() {
        SpatialRegistry registry = (SpatialRegistry) project.getSpatialRegistry();
        registry.applyPendingChanges();
        
        if (System.getProperty("debug.spatial") != null) {
            validateSpatialConsistency();
        }
    }
    
    private boolean areAnyLivingAgentsAlive() {
        for (AgentLayer layer : project.getAgentLayers()) {
            List<Agent> agents = layer.getAgents();
            for (Agent agent : agents) {
                if (agent instanceof LivingAgent la && la.isAlive()) {
                    return true;
                }
            }
        }
        return false;
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
        SpatialRegistry registry = (SpatialRegistry) project.getSpatialRegistry();
        List<Agent> layerAgents = project.getAgentLayers().stream()
            .flatMap(l -> l.getAgents().stream())
            .collect(Collectors.toList());
        
        List<Agent> registryAgents = registry.getAllAgents();
        
        if (layerAgents.size() != registryAgents.size()) {
            SimulationLogger.warning("Spatial inconsistency: layers=%d, registry=%d%n", layerAgents.size(), registryAgents.size());
        }
    }
    
    private void reportTickProgress(long tickStart) {
        long tickDuration = System.nanoTime() - tickStart;
        updatePerformanceMetrics(tickDuration);

        if (timeManager.getTickCount() % 100 == 0) {
            exportPeriodicSnapshot();
            printExportStatus();
        }

        if (timeManager.getTickCount() % 10 == 0) {
            reportProgress();
        }
    }
    
    private void exportPeriodicSnapshot() {
        long currentTick = timeManager.getTickCount();
        checkAndClearStuckExports();

        SimulationLogger.info("[Export] Scheduling export for tick %d at %s%n", 
            currentTick, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        ExportTask task = new ExportTask(project, currentTick, outputDir);
        exportQueue.offer(task);

        if (exportInProgress.compareAndSet(false, true)) {
            exportExecutor.submit(() -> {
                try {
                    processExportQueue();
                } catch (Exception e) {
                    SimulationLogger.warning("[Export] Error in export processing: " + e.getMessage());
                    e.printStackTrace();
                    exportInProgress.set(false);
                }
            });
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
            if (!exportQueue.isEmpty() && exportInProgress.compareAndSet(false, true)) {
                exportExecutor.submit(this::processExportQueue);
            }
        }
    }
    
    private void processSingleExport(ExportTask task) {
        if (task == null || task.project == null) {
            SimulationLogger.warning("[Export] ERROR: Invalid export task");
            return;
        }

        String filename = String.format("%s/snapshot_tick_%d.csv", task.outputDir, task.tick);
        ProjectPersistenceService persistenceService = new ProjectPersistenceService();
        try {
            boolean success = persistenceService.exportToCSV(task.project, filename, task.tick);
            if (success) {
                SimulationLogger.info("[Export] DONE processing tick %d%n", task.tick);
            }
        } catch (IOException ex) {
            Logger.getLogger(SimulationEngine.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void checkAndClearStuckExports() {
        if (exportQueue.size() > 10) {
            SimulationLogger.warning("[Export] Too many queued exports (" + exportQueue.size() + "), clearing queue");
            exportQueue.clear();
            exportInProgress.set(false);
        }
    }
    
    private Project createCompleteProjectSnapshot(Project original, List<Agent> agents) {
        Project snapshot = new Project(original.getName() + "_snapshot_tick_" + System.currentTimeMillis());

        snapshot.setDefaultAgentSearchRadius(original.getDefaultAgentSearchRadius());
        snapshot.setDefaultHatchingProbability(original.getDefaultHatchingProbability());
        snapshot.setDefaultAgentStep(original.getDefaultAgentStep());
        snapshot.setDefaultBirthRate(original.getDefaultBirthRate());
        snapshot.setDefaultMaxAgentAge(original.getDefaultMaxAgentAge());

        for (Layer layer : original.getLayers()) {
            if (layer instanceof RasterLayer || layer instanceof InterpolatedRasterLayer) {
                snapshot.addLayer(layer);
            }
        }

        Map<String, List<Agent>> agentsByLayerName = new HashMap<>();
        for (Agent agent : agents) {
            for (AgentLayer originalLayer : original.getAgentLayers()) {
                if (originalLayer.getAgents().contains(agent)) {
                    agentsByLayerName.computeIfAbsent(originalLayer.getName(), k -> new ArrayList<>()).add(agent);
                    break;
                }
            }
        }

        for (Map.Entry<String, List<Agent>> entry : agentsByLayerName.entrySet()) {
            AgentLayer layerSnapshot = new AgentLayer(entry.getKey(), null, null);
            layerSnapshot.addAgents(entry.getValue());
            snapshot.addLayer(layerSnapshot);
        }

        snapshot.setSpatialRegistry(original.getSpatialRegistry());
        return snapshot;
    }

    private static class ExportTask {
        final Project project;
        final long tick;
        final String outputDir;

        ExportTask(Project project, long tick, String outputDir) {
            this.project = project;
            this.tick = tick;
            this.outputDir = outputDir;
        }
    }

    private void cleanup() {
        SimulationLogger.info("Cleaning up simulation resources...");
        exportFinalSnapshotNow();
        waitForExportsToComplete();
        mergeSnapshots();
        SimulationLogger.info("Cleanup completed successfully");
    }

    private void exportFinalSnapshotNow() {
        long currentTick = timeManager.getTickCount();
        try {
            File dir = new File(outputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                SimulationLogger.severe("[EXPORT] Could not create output directory: " + outputDir);
                return;
            }

            String filename = String.format("%s/snapshot_tick_%d.csv", outputDir, currentTick);
            ProjectPersistenceService persistenceService = new ProjectPersistenceService();

            List<Agent> agentsSnapshot = new ArrayList<>();
            for (AgentLayer layer : project.getAgentLayers()) {
                synchronized (layer) {
                    agentsSnapshot.addAll(new ArrayList<>(layer.getAgents()));
                }
            }

            Project snapshot = createCompleteProjectSnapshot(project, agentsSnapshot);
            persistenceService.exportToCSV(snapshot, filename, currentTick);
            SimulationLogger.info("[EXPORT] FINAL Snapshot exported to: " + filename);

        } catch (Exception e) {
            SimulationLogger.warning("[EXPORT] Error exporting final snapshot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void waitForExportsToComplete() {
        if (exportExecutor != null) {
            exportExecutor.shutdown();
            try {
                if (!exportExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    exportExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                exportExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void mergeSnapshots() {
        SimulationLogger.info("Merging snapshot files...");
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String mergedFilename = String.format("merged_snapshots_%s.csv", timestamp);
            SnapshotMerger.mergeSnapshotsAndCleanup(outputDir, mergedFilename);
        } catch (Exception e) {
            SimulationLogger.warning("Failed to merge snapshots: " + e.getMessage());
        }
    }

    private void processAgentLayersWithImmediateUpdates() {
        List<AgentLayer> layers = project.getAgentLayers();
        long adaptiveTimeout = calculateAdaptiveTimeout(layers);

        for (AgentLayer layer : layers) {
            try {
                CompletableFuture<Void> layerFuture = CompletableFuture.runAsync(() -> layer.update(project));
                layerFuture.get(adaptiveTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                SimulationLogger.warning("WARNING: Layer " + layer.getName() + " timed out.");
            } catch (Exception e) {
                SimulationLogger.warning("Error in layer " + layer.getName() + ": " + e.getMessage());
            }
        }
    }
    
    private long calculateAdaptiveTimeout(List<AgentLayer> layers) {
        int totalAgents = layers.stream().mapToInt(l -> l.getAgents().size()).sum();
        return 15000 + (totalAgents / 100) * 50;
    }

    private void cleanupResources() {
        if (resourcesCleaned.compareAndSet(false, true)) {
            SimulationLogger.info("=== Cleaning up simulation resources ===");
            if (simulationExecutor != null && !simulationExecutor.isTerminated()) {
                try {
                    simulationExecutor.gracefulShutdown(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    simulationExecutor.shutdownNow();
                }
            }
            if (project != null) {
                project.getAgentLayers().forEach(AgentLayer::shutdown);
                project.getLayers().forEach(layer -> {
                    if (layer instanceof MemoryMappedRasterLayer mmrl) {
                        mmrl.dispose();
                    }
                });
            }
            System.gc();
        }
    }

    public void stop() {
        running.set(false);
        cleanupResources();
    }
    
    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>();
        state.put("running", running.get());
        state.put("paused", paused.get());
        state.put("tick", timeManager.getTickCount());
        state.put("totalAgents", getTotalAgentCount());
        state.put("avgTickTime", averageTickTime);
        state.put("timestamp", System.currentTimeMillis());
        return state;
    }
    
    private int getTotalAgentCount() {
        return project.getAgentLayers().stream().mapToInt(l -> l.getAgents().size()).sum();
    }
    
    private void reportProgress() {
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
            .findFirst().orElse(null);

        long adults = 0, pupae = 0;
        if (mosquitoLayer != null) {
            for (Agent agent : mosquitoLayer.getAgents()) {
                if (agent instanceof LivingAgent la && la.isAlive()) {
                    if (la.getStage() == LifecycleStage.ADULT) adults++;
                    else if (la.getStage() == LifecycleStage.PUPA) pupae++;
                }
            }
        }

        long totalLarvae = 0, totalEggs = 0, tanksWithWater = 0;
        double avgWater = 0;
        for (AgentLayer layer : project.getAgentLayers()) {
            for (Agent agent : layer.getAgents()) {
                if (agent instanceof InertAgent ia) {
                    totalLarvae += ia.getLarvalCount();
                    totalEggs += ia.getEggCount();
                    avgWater += ia.getWaterVolume();
                    tanksWithWater++;
                }
            }
        }

        SimulationLogger.info(String.format("Tick: %d | Adults: %d | Pupae: %d | Larvae: %d | Eggs: %d | Memory: %.1f MB",
            timeManager.getTickCount(), adults, pupae, totalLarvae, totalEggs,
            Runtime.getRuntime().totalMemory() / (1024.0 * 1024.0)));
    }
    
    private void adaptiveSleep(long tickDuration) {
        long targetTickTime = 16_666_667L;
        if (tickDuration < targetTickTime) {
            try {
                TimeUnit.NANOSECONDS.sleep(targetTickTime - tickDuration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void updatePerformanceMetrics(long tickDuration) {
        lastTickTime = tickDuration;
        if (averageTickTime == 0) {
            averageTickTime = tickDuration / 1_000_000.0;
        } else {
            double alpha = 2.0 / (tickTimeWindow + 1);
            averageTickTime = (1 - alpha) * averageTickTime + alpha * (tickDuration / 1_000_000.0);
        }
    }
    
    private void printExportStatus() {
        SimulationLogger.info("[Export Status] Queue: %d, Active: %b", exportQueue.size(), exportInProgress.get());
    }
}