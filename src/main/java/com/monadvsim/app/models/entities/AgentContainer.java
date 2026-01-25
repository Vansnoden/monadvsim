package com.monadvsim.app.models.entities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    
    
    public AgentContainer(int partitionCount) {
        this.partitionCount = partitionCount;
        this.partitions = new ArrayList<>(partitionCount);
        this.agentRegistry = new ConcurrentHashMap<>();
        this.agentPartitionMap = new ConcurrentHashMap<>();

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
        newAgents.addAll(agents);
        size.addAndGet(agents.size());
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
    
    
    private void processNewAgents(){
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
            }
        }
    }
    
    
    private void processDeadAgents(){
        while (!deadAgents.isEmpty()) {
            Agent agent = deadAgents.poll();
            if (agent != null) {
                registryLock.readLock().lock();
                try {
                    Integer partitionIndex = agentPartitionMap.get(agent.getId());
                    if (partitionIndex != null) {
                        partitions.get(partitionIndex).remove(agent);
                        agentRegistry.remove(agent.getId());
                        agentPartitionMap.remove(agent.getId());
                        size.decrementAndGet();
                    }
                } finally {
                    registryLock.readLock().unlock();
                }
            }
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
        List<Agent> allAgents = new ArrayList<>(size.get());
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
    
    
    // Process agents with partition-level batching
//    public void processWithBatching(Consumer<List<Agent>> batchProcessor, 
//                                   int batchSize) {
//        partitions.parallelStream().forEach(partition -> {
//            List<Agent> batch = new ArrayList<>(batchSize);
//            for (Agent agent : partition) {
//                batch.add(agent);
//                if (batch.size() >= batchSize) {
//                    batchProcessor.accept(batch);
//                    batch.clear();
//                }
//            }
//            if (!batch.isEmpty()) {
//                batchProcessor.accept(batch);
//            }
//        });
//    }
    
    
    public void processWithBatching(Consumer<List<Agent>> batchProcessor, int batchSize) {
        // Create a snapshot of partitions to avoid concurrent modification
        List<List<Agent>> partitionSnapshots = new ArrayList<>();

        for (int i = 0; i < partitionCount; i++) {
            partitionSnapshots.add(new ArrayList<>(partitions.get(i)));
        }

        // Process snapshots instead of live partitions
        partitionSnapshots.parallelStream().forEach(partition -> {
            List<Agent> batch = new ArrayList<>(batchSize);
            for (Agent agent : partition) {
                batch.add(agent);
                if (batch.size() >= batchSize) {
                    batchProcessor.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                batchProcessor.accept(batch);
            }
        });
    }
    
    
    // Getters
    public int size() {
        return size.get();
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
    
    
}
