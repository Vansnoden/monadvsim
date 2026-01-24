package com.monadvsim.app;

import com.monadvsim.app.models.entities.*;
import com.monadvsim.app.models.engine.ThreadSafeRuleEngine;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadSafetyTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Thread Safety Test ===");
        
        // Test 1: Concurrent Agent Updates
        testConcurrentAgentUpdates();
        
        // Test 2: Rule Engine Thread Safety
        testRuleEngineThreadSafety();
        
        // Test 3: Agent Container Thread Safety
        testAgentContainerThreadSafety();
        
        System.out.println("=== All Tests Passed ===");
    }
    
    private static void testConcurrentAgentUpdates() throws Exception {
        System.out.println("\n-> Test 1: Concurrent Agent Updates");
        
        LivingAgent agent = new LivingAgent(0, 0);
        int threadCount = 10;
        int updatesPerThread = 10000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < updatesPerThread; j++) {
                        agent.incrementAge();
                        agent.move(0.001, 0.001);
                        
                        // Toggle gravid state
                        agent.setGravid(j % 2 == 0);
                        
                        // Verify consistency
                        if (agent.getAge() < 0) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        int expectedAge = threadCount * updatesPerThread;
        System.out.printf("-> Expected age: %d, Actual age: %d%n", expectedAge, agent.getAge());
        System.out.printf("-> Errors: %d%n", errors.get());
        
        if (agent.getAge() != expectedAge || errors.get() > 0) {
            throw new AssertionError("-> Concurrent agent update test failed");
        }
        
        System.out.println("-> Concurrent Agent Updates test passed");
    }
    
    private static void testRuleEngineThreadSafety() throws Exception {
        System.out.println("\n-> Test 2: Rule Engine Thread Safety");
        
        ThreadSafeRuleEngine ruleEngine = new ThreadSafeRuleEngine();
        int threadCount = 20;
        int evaluationsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        
        // Create a simple project and agent for testing
        Project project = new Project("Test");
        LivingAgent agent = new LivingAgent(0, 0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < evaluationsPerThread; j++) {
                        boolean result = ruleEngine.evaluate("age < 100 && random < 0.5", agent, project);
                        if (result || !result) { // Just checking it doesn't crash
                            successes.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    System.err.println("-> Rule evaluation error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        executor.shutdown();
        
        int totalEvaluations = threadCount * evaluationsPerThread;
        System.out.printf("-> Total evaluations: %d%n", totalEvaluations);
        System.out.printf("-> Successes: %d, Failures: %d%n", successes.get(), failures.get());
        
        if (failures.get() > 0) {
            throw new AssertionError("-> Rule engine thread safety test failed");
        }
        
        System.out.println("-> Rule Engine Thread Safety test passed");
    }
    
    private static void testAgentContainerThreadSafety() throws Exception {
        System.out.println("\n-> Test 3: Agent Container Thread Safety");
        
        ThreadSafeAgentContainer container = new ThreadSafeAgentContainer(4);
        int threadCount = 8;
        int agentsPerThread = 1000;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger addedCount = new AtomicInteger(0);
        AtomicInteger removedCount = new AtomicInteger(0);
        
        // Phase 1: Concurrent additions
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < agentsPerThread; j++) {
                        Agent agent = new LivingAgent(j * 0.001, j * 0.001);
                        container.addAgent(agent);
                        addedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        latch.await();
        
        // Apply pending operations
        container.applyPendingOperations();
        
        System.out.printf("-> Added %d agents, Container size: %d%n", 
            addedCount.get(), container.size());
        
        if (container.size() != threadCount * agentsPerThread) {
            throw new AssertionError("-> Agent count mismatch after concurrent additions");
        }
        
        // Phase 2: Concurrent processing
        CountDownLatch processLatch = new CountDownLatch(threadCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                container.processInParallel(agent -> {
                    processedCount.incrementAndGet();
                    // Mark some agents for removal
                    if (Math.random() < 0.1) {
                        container.markForRemoval(agent);
                        removedCount.incrementAndGet();
                    }
                });
                processLatch.countDown();
            });
        }
        
        processLatch.await();
        
        // Apply removals
        container.applyPendingOperations();
        
        System.out.printf("-> Processed %d agents, Removed %d agents, Final size: %d%n",
            processedCount.get(), removedCount.get(), container.size());
        
        System.out.println("-> Agent Container Thread Safety test passed");
        
        executor.shutdown();
    }
}