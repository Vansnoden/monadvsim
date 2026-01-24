package com.monadvsim.app;

import com.monadvsim.app.models.engine.*;
import com.monadvsim.app.models.entities.*;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class IncrementalUpdateTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Testing Incremental Spatial Updates ===\n");
        
        // Test 1: Newborn agents immediately available for queries
        testImmediateNewbornAvailability();
        
        // Test 2: Dead agents immediately removed from queries
        testImmediateDeadRemoval();
        
        // Test 3: Moving agents immediately reflected in queries
        testImmediateMoveUpdates();
        
        // Test 4: Concurrent updates thread safety
        testConcurrentUpdates();
        
        System.out.println("\n✅ All incremental update tests passed!");
    }
    
    private static void testImmediateNewbornAvailability() {
        System.out.println("Test 1: Newborn agents immediately available for queries");
        
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, 1, 1);
        IncrementalSpatialRegistry registry = new IncrementalSpatialRegistry(bounds, 0.1);
        AgentLifecycleManager lifecycle = new AgentLifecycleManager(registry);
        
        // Create initial agent
        LivingAgent agent1 = new LivingAgent(0.5, 0.5);
        registry.registerAgent(agent1);
        
        // Query should find only agent1
        List<Agent> nearby1 = registry.getNearbyAgents(0.5, 0.5, 0.2);
        System.out.printf("  Initial query: %d agents found%n", nearby1.size());
        
        // Create newborn agent immediately
        LivingAgent newborn = new LivingAgent(0.52, 0.52);
        lifecycle.immediateBirth(LivingAgent.class, 0.52, 0.52);
        
        // Query should IMMEDIATELY find both agents
        List<Agent> nearby2 = registry.getNearbyAgents(0.5, 0.5, 0.2);
        System.out.printf("  After birth query: %d agents found (expected 2)%n", nearby2.size());
        
        if (nearby2.size() != 2) {
            throw new AssertionError("Newborn agent not immediately available in spatial queries");
        }
        
        System.out.println("  ✓ Newborn agents immediately available\n");
    }
    
    private static void testImmediateDeadRemoval() {
        System.out.println("Test 2: Dead agents immediately removed from queries");
        
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, 1, 1);
        IncrementalSpatialRegistry registry = new IncrementalSpatialRegistry(bounds, 0.1);
        AgentLifecycleManager lifecycle = new AgentLifecycleManager(registry);
        
        // Create agents
        LivingAgent agent1 = new LivingAgent(0.5, 0.5);
        LivingAgent agent2 = new LivingAgent(0.6, 0.6);
        
        registry.registerAgent(agent1);
        registry.registerAgent(agent2);
        
        // Query should find both agents
        List<Agent> nearby1 = registry.getNearbyAgents(0.5, 0.5, 0.2);
        System.out.printf("  Initial query: %d agents found%n", nearby1.size());
        
        // Kill agent2 immediately
        lifecycle.immediateDeath(agent2.getId());
        
        // Query should IMMEDIATELY find only agent1
        List<Agent> nearby2 = registry.getNearbyAgents(0.5, 0.5, 0.2);
        System.out.printf("  After death query: %d agents found (expected 1)%n", nearby2.size());
        
        if (nearby2.size() != 1) {
            throw new AssertionError("Dead agent not immediately removed from spatial queries");
        }
        
        System.out.println("  ✓ Dead agents immediately removed\n");
    }
    
    private static void testImmediateMoveUpdates() {
        System.out.println("Test 3: Moving agents immediately reflected in queries");
        
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, 2, 2);
        IncrementalSpatialRegistry registry = new IncrementalSpatialRegistry(bounds, 0.5);
        AgentLifecycleManager lifecycle = new AgentLifecycleManager(registry);
        
        // Create agent in cell (0,0)
        LivingAgent agent = new LivingAgent(0.2, 0.2);
        registry.registerAgent(agent);
        
        // Query cell (0,0) should find agent
        List<Agent> inCell1 = registry.getAgentsInCell(new GridCell(0, 0));
        System.out.printf("  Agent in cell (0,0): %s%n", 
            inCell1.contains(agent) ? "FOUND" : "NOT FOUND");
        
        // Move agent to cell (1,1)
        agent.setX(1.2);
        agent.setY(1.2);
        lifecycle.immediateMove(agent);
        
        // Query cell (1,1) should IMMEDIATELY find agent
        List<Agent> inCell2 = registry.getAgentsInCell(new GridCell(1, 1));
        System.out.printf("  Agent in cell (1,1) after move: %s%n",
            inCell2.contains(agent) ? "FOUND" : "NOT FOUND");
        
        // Query cell (0,0) should IMMEDIATELY NOT find agent
        List<Agent> inCell3 = registry.getAgentsInCell(new GridCell(0, 0));
        System.out.printf("  Agent in cell (0,0) after move: %s%n",
            inCell3.contains(agent) ? "FOUND (ERROR)" : "NOT FOUND (CORRECT)");
        
        if (inCell2.isEmpty() || !inCell3.isEmpty()) {
            throw new AssertionError("Agent move not immediately reflected in spatial registry");
        }
        
        System.out.println("  ✓ Moving agents immediately reflected\n");
    }
    
    private static void testConcurrentUpdates() throws Exception {
        System.out.println("Test 4: Concurrent updates thread safety");
        
        Rectangle2D bounds = new Rectangle2D.Double(0, 0, 10, 10);
        IncrementalSpatialRegistry registry = new IncrementalSpatialRegistry(bounds, 1.0);
        AgentLifecycleManager lifecycle = new AgentLifecycleManager(registry);
        
        int threadCount = 10;
        int operationsPerThread = 1000;
        
        // Create threads for concurrent operations
        List<Thread> threads = new ArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                Random rand = new Random();
                
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        // Random operation
                        int op = rand.nextInt(3);
                        
                        switch (op) {
                            case 0: // Birth
                                double x = rand.nextDouble() * 10;
                                double y = rand.nextDouble() * 10;
                                lifecycle.immediateBirth(LivingAgent.class, x, y);
                                break;
                                
                            case 1: // Move random agent
                                List<Agent> allAgents = registry.getAllAgents();
                                if (!allAgents.isEmpty()) {
                                    Agent agent = allAgents.get(rand.nextInt(allAgents.size()));
                                    agent.setX(rand.nextDouble() * 10);
                                    agent.setY(rand.nextDouble() * 10);
                                    lifecycle.immediateMove(agent);
                                }
                                break;
                                
                            case 2: // Death random agent
                                List<Agent> agents = registry.getAllAgents();
                                if (!agents.isEmpty()) {
                                    Agent agent = agents.get(rand.nextInt(agents.size()));
                                    lifecycle.immediateDeath(agent.getId());
                                }
                                break;
                        }
                        
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        System.err.println("Concurrent operation error: " + e.getMessage());
                    }
                }
            });
            
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify consistency
        Map<String, Object> stats = registry.getStatistics();
        int totalAgents = (int) stats.get("totalAgents");
        int totalInserts = (int) stats.get("totalInserts");
        int totalRemoves = (int) stats.get("totalRemoves");
        
        System.out.printf("  Total agents: %d%n", totalAgents);
        System.out.printf("  Total inserts: %d%n", totalInserts);
        System.out.printf("  Total removes: %d%n", totalRemoves);
        System.out.printf("  Errors: %d%n", errors.get());
        
        // Basic consistency check
        if (totalAgents != (totalInserts - totalRemoves)) {
            System.err.printf("  Consistency error: %d != (%d - %d)%n", 
                totalAgents, totalInserts, totalRemoves);
            throw new AssertionError("Spatial registry consistency violated");
        }
        
        if (errors.get() > 0) {
            throw new AssertionError("Thread safety errors detected");
        }
        
        System.out.println("  ✓ Concurrent updates thread-safe\n");
    }
}