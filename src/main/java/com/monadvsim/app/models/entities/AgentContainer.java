package com.monadvsim.app.models.entities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;



public class AgentContainer {

    private final List<ConcurrentLinkedQueue<Agent>> partitions;
    private final Map<String, Agent> agentRegistry; // For quick lookup by ID
    private final Map<String, Integer> agentPartitionMap; // Tracks which partition an agent is in
    private final ReentrantReadWriteLock registryLock = new ReentrantReadWriteLock();
    private final int partitionCount;
    private final AtomicInteger size = new AtomicInteger(0);
    // Dead agent buffer for safe removal
    private final ConcurrentLinkedQueue<Agent> deadAgents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Agent> newAgents = new ConcurrentLinkedQueue<>();
    private final ExecutorService batchExecutor;
    // performance
    private static final int DEAD_AGENT_BATCH_SIZE = 500;
    
    
    public AgentContainer(int partitionCount) {
        this.partitionCount = partitionCount;
        this.partitions = new ArrayList<>(partitionCount);
        this.agentRegistry = new ConcurrentHashMap<>();
        this.agentPartitionMap = new ConcurrentHashMap<>();
        // Initialize batch executor
        int processors = Runtime.getRuntime().availableProcessors();
        this.batchExecutor = Executors.newFixedThreadPool(
            Math.max(2, processors),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "BatchProcessor-" + count.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            }
        );
        for (int i = 0; i < partitionCount; i++) {
            partitions.add(new ConcurrentLinkedQueue<>());
        }
    }
    
    
    // thread safe operations
    public boolean addAgent(Agent agent) {
        newAgents.add(agent);
        size.incrementAndGet();
        return true;
    }
    
    
    // Batch add multiple agents
    public void addAgents(Collection<Agent> agents) {
        synchronized (newAgents) {
            newAgents.addAll(agents);
            size.addAndGet(agents.size());
        }
    }
    
    
    // Mark agent for removal (thread-safe)
    public void markForRemoval(Agent agent) {
        deadAgents.add(agent);
    }
    
    
    // Apply pending operations (adds and removals) form a single thread
    // ideally the main threat
    public void applyPendingOperations() {
        processNewAgents();
        processDeadAgents();
    }
    
    
    private void processNewAgents() {
        synchronized (newAgents) {
            int addedCount = 0;
            while (!newAgents.isEmpty()) {
                Agent agent = newAgents.poll();
                if (agent != null) {
                    int partitionIndex = Math.abs(agent.getId().hashCode()) % partitionCount;
                    partitions.get(partitionIndex).add(agent);

                    registryLock.writeLock().lock();
                    try {
                        agentRegistry.put(agent.getId(), agent);
                        agentPartitionMap.put(agent.getId(), partitionIndex);
                    } finally {
                        registryLock.writeLock().unlock();
                    }
                    addedCount++;
                }
            }
            // Only update size after all are processed
            if (addedCount > 0) {
                size.addAndGet(addedCount);
            }
        }
    }
    
    
    private void processDeadAgents() {
        // Use optimized version for large batches
        if (deadAgents.size() > 100) {
            processDeadAgentsOptimized();
        } else {
            // Original small batch processing
            while (!deadAgents.isEmpty()) {
                Agent agent = deadAgents.poll();
                if (agent != null) {
                    processSingleDeadAgent(agent);
                }
            }
        }
    }
    
    private void processSingleDeadAgent(Agent agent) {
        registryLock.readLock().lock();
        try {
            Integer partitionIndex = agentPartitionMap.get(agent.getId());
            if (partitionIndex != null) {
                partitions.get(partitionIndex).remove(agent);
                agentRegistry.remove(agent.getId());
                agentPartitionMap.remove(agent.getId());
            }
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    
    // Get agents from a specific partition (thread-safe for iteration)
    public List<Agent> getAgentsFromPartition(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= partitionCount) {
            return Collections.emptyList();
        }
        return new ArrayList<>(partitions.get(partitionIndex));
    }
    
    
    // Get all agents (for spatial registry updates)
    
    public List<Agent> getAllAgents() {
        // Ensure pending operations are processed first
        applyPendingOperations();

        List<Agent> allAgents = new ArrayList<>(Math.max(0, size.get()));
        registryLock.readLock().lock();
        try {
            allAgents.addAll(agentRegistry.values());
        } finally {
            registryLock.readLock().unlock();
        }
        return allAgents;
    }
    
    
    // Find agent by ID (thread-safe)
    public Agent getAgentById(String id) {
        registryLock.readLock().lock();
        try {
            return agentRegistry.get(id);
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    
    // Process agents in parallel using partitioned processing
    public void processInParallel(Consumer<Agent> processor) {
        partitions.parallelStream().forEach(partition -> {
            for (Agent agent : partition) {
                processor.accept(agent);
            }
        });
    }
    
    
    public void processWithBatching(Consumer<List<Agent>> batchProcessor, int batchSize) throws ExecutionException {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Use read lock to ensure consistency while taking snapshots
        registryLock.readLock().lock();

        try {
            // Process each partition
            for (int i = 0; i < partitionCount; i++) {
                // Take snapshot under lock protection
                final int partitionIndex = i;
                List<Agent> snapshot = new ArrayList<>(partitions.get(i));

                if (snapshot.isEmpty()) continue;

                // Process snapshot in batches
                for (int start = 0; start < snapshot.size(); start += batchSize) {
                    int end = Math.min(start + batchSize, snapshot.size());
                    final List<Agent> batch = new ArrayList<>(snapshot.subList(start, end));

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            batchProcessor.accept(batch);
                        } catch (Exception e) {
                            System.err.println("Error in batch processing for partition " + 
                                             partitionIndex + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    }, batchExecutor);

                    futures.add(future);
                }
            }
        } finally {
            registryLock.readLock().unlock();
        }

        // Wait for all batches to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Batch processing interrupted");
        } catch (ExecutionException e) {
            System.err.println("Error in batch processing: " + e.getCause().getMessage());
        }
    }

    
    // Getters
    public int size() {
        // Apply pending operations to get accurate count
        applyPendingOperations();
        return Math.max(0, size.get());
    }
    
    
    public int getPartitionCount() {
        return partitionCount;
    }
    
    
    public boolean isEmpty() {
        return size.get() == 0;
    }
    
    
    // Get statistics about the container
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAgents", size.get());
        stats.put("partitions", partitionCount);
        stats.put("pendingAdds", newAgents.size());
        stats.put("pendingRemovals", deadAgents.size());
        
        // Partition sizes
        Map<Integer, Integer> partitionSizes = new HashMap<>();
        for (int i = 0; i < partitionCount; i++) {
            partitionSizes.put(i, partitions.get(i).size());
        }
        stats.put("partitionSizes", partitionSizes);
        
        return stats;
    }
    
    
    public void shutdown() {
        if (batchExecutor != null) {
            batchExecutor.shutdown();
            try {
                if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    batchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    
    // Bulk mark for removal (more efficient)
    public void markMultipleForRemoval(Collection<Agent> agents) {
        synchronized (deadAgents) {
            deadAgents.addAll(agents);
            // DON'T decrement size here
        }
    }

    // Optimized bulk removal processing
    private void processDeadAgentsOptimized() {
        if (deadAgents.isEmpty()) return;

        List<Agent> batch = new ArrayList<>(DEAD_AGENT_BATCH_SIZE);
        synchronized (deadAgents) {
            int count = 0;
            while (!deadAgents.isEmpty() && count < 1000) {
                Agent agent = deadAgents.poll();
                if (agent != null) {
                    batch.add(agent);
                    count++;
                }
            }
        }

        if (!batch.isEmpty()) {
            size.addAndGet(-batch.size());
            Map<Integer, List<Agent>> partitionMap = new HashMap<>();

            // Group by partition for efficient removal
            for (Agent agent : batch) {
                registryLock.readLock().lock();
                try {
                    Integer partitionIndex = agentPartitionMap.get(agent.getId());
                    if (partitionIndex != null) {
                        partitionMap.computeIfAbsent(partitionIndex, k -> new ArrayList<>())
                                   .add(agent);
                    }
                } finally {
                    registryLock.readLock().unlock();
                }
            }

            // Remove from partitions
            for (Map.Entry<Integer, List<Agent>> entry : partitionMap.entrySet()) {
                List<Agent> partitionAgents = entry.getValue();
                ConcurrentLinkedQueue<Agent> partition = partitions.get(entry.getKey());
                partition.removeAll(partitionAgents);
            }

            // Remove from tracking maps
            registryLock.writeLock().lock();
            try {
                for (Agent agent : batch) {
                    agentRegistry.remove(agent.getId());
                    agentPartitionMap.remove(agent.getId());
                }
            } finally {
                registryLock.writeLock().unlock();
            }
        }
    }
    
}
