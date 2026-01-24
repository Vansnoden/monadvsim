package com.monadvsim.app.models.engine;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.utils.ManagedExecutorService;
import com.monadvsim.app.models.utils.ResourceManager;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class SimulationEngine implements Runnable, Closeable {
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
        
        // Register shutdown hook
        this.simulationExecutor.addShutdownHook(this::cleanupResources);
    }

    
    @Override
    public void run() {
        try {
            // Track simulation start
            resourceManager.track("SimulationEngine", this, "Simulation Engine Instance");
            
            // Run simulation
            simulationLoop();
            
        } finally {
            // Ensure cleanup
            cleanupResources();
        }
    }
    
    /**
     * Clean up all resources
     */
    private void cleanupResources() {
        if (resourcesCleaned.compareAndSet(false, true)) {
            System.out.println("\n=== Cleaning up simulation resources ===");
            
            // 1. Shutdown executor service
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
            
            // 2. Clean up agent layers
            if (project != null) {
                System.out.println("Cleaning up agent layers...");
                project.getAgentLayers().forEach(layer -> {
                    AgentLayer al = (AgentLayer) layer; 
                    al.shutdown();
                });
            }
            
            // 3. Clean up spatial registry
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
    
    /**
     * Get engine statistics including resource usage
     */
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
    
    
    private void simulationLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // Check if paused
            while (paused.get() && running.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            
            long tickStartTime = System.nanoTime();
            
            try {
                // Acquire lock for thread-safe tick processing
                simulationLock.lock();
                processTick();
            } finally {
                simulationLock.unlock();
            }
            
            long tickDuration = System.nanoTime() - tickStartTime;
            updatePerformanceMetrics(tickDuration);
            
            // Adaptive sleep to maintain tick rate
            adaptiveSleep(tickDuration);
        }
    }
    
    /**
     * Process a single tick (thread-safe when called with lock)
     */
    private void processTick() {
        // Advance time
        if (!timeManager.tick()) {
            running.set(false);
            return;
        }
        
        // Update environment
        project.updateEnvironment(timeManager.getCurrentFrameIndex());
        
        // Update spatial registry
        updateSpatialRegistry();
        
        // Process agent layers in parallel
        processAgentLayers();
        
        // Report progress periodically
        if (timeManager.getTickCount() % 10 == 0) {
            reportProgress();
        }
        
        // Log performance periodically
        if (timeManager.getTickCount() % 100 == 0) {
            logPerformance();
        }
    }
    
    /**
     * Thread-safe spatial registry update
     */
    private void updateSpatialRegistry() {
        List<Agent> allAgents = project.getAgentLayers().stream()
            .flatMap(l -> l.getAgents().stream())
            .collect(Collectors.toList());
        
        metrics.recordAgentProcessed();
        
        // Use incremental update for better performance
        spatialRegistry.updateIncremental(allAgents);
    }
    
    /**
     * Process agent layers in parallel
     */
    private void processAgentLayers() {
        List<AgentLayer> layers = project.getAgentLayers();
        
        // Process layers in parallel using CompletableFuture
        List<CompletableFuture<Void>> futures = layers.stream()
            .map(layer -> CompletableFuture.runAsync(() -> {
                try {
                    layer.update(project);
                    updateLayerStats(layer.getName(), layer.getAgents().size());
                } catch (Exception e) {
                    System.err.println("Error in layer " + layer.getName() + ": " + e.getMessage());
                }
            }, simulationExecutor))
            .collect(Collectors.toList());
        
        // Wait for all layers to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        } catch (ExecutionException e) {
            System.err.println("Error processing layers: " + e.getMessage());
        }
    }
    
    private void updateLayerStats(String layerName, int agentCount) {
        layerStats.computeIfAbsent(layerName, k -> new AtomicInteger(0))
                 .set(agentCount);
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
    
    private int getTotalAgentCount() {
        return project.getAgentLayers().stream()
            .mapToInt(l -> l.getAgents().size())
            .sum();
    }
    
    private void reportProgress() {
        AgentLayer mosquitoLayer = project.getAgentLayers().stream()
            .filter(l -> l.getName().equalsIgnoreCase("Mosquitoes"))
            .findFirst().orElse(null);

        long adults = (mosquitoLayer != null) ? mosquitoLayer.getAgents().size() : 0;
        
        long totalLarvae = project.getAgentLayers().stream()
            .flatMap(l -> l.getAgents().stream())
            .filter(a -> a instanceof InertAgent)
            .mapToLong(a -> ((InertAgent) a).getLarvalCount())
            .sum();

        System.out.println(String.format("Tick: %d | Adults: %d | Larvae: %d | Memory: %.1f MB", 
            timeManager.getTickCount(), adults, totalLarvae,
            Runtime.getRuntime().totalMemory() / (1024.0 * 1024.0)));
    }
    
    /**
     * Pause the simulation
     */
    public void pause() {
        paused.set(true);
        System.out.println("Simulation paused");
    }
    
    /**
     * Resume the simulation
     */
    public void resume() {
        paused.set(false);
        System.out.println("Simulation resumed");
    }
    
    
    /**
     * Get simulation state
     */
    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>();
        state.put("running", running.get());
        state.put("paused", paused.get());
        state.put("tick", timeManager.getTickCount());
        state.put("totalAgents", getTotalAgentCount());
        state.put("avgTickTime", averageTickTime);
//        state.put("threadPoolActive", (simulationExecutor).getActiveCount());
//        state.put("threadPoolQueue", (simulationExecutor).getQueue().size());
        return state;
    }
    
    /**
     * Graceful shutdown
     */
    private void shutdown() {
        running.set(false);
        
        // Shutdown simulation executor
        simulationExecutor.shutdown();
        try {
            if (!simulationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                simulationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            simulationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown agent layers
        project.getAgentLayers().forEach(layer -> {
            AgentLayer al = (AgentLayer) layer; 
            al.shutdown();
        });
        
        System.out.println("Simulation engine shutdown complete");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
}