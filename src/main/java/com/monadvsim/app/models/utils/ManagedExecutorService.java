package com.monadvsim.app.models.utils;


import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


// Managed executor service with resource tracking and cleanup
public class ManagedExecutorService implements ExecutorService {
    private final ExecutorService delegate;
    private final String name;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger failedTasks = new AtomicInteger(0);
    private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
    private final ReentrantLock shutdownLock = new ReentrantLock();
    private volatile boolean shuttingDown = false;
    
    // Statistics
    private long startTime = System.currentTimeMillis();
    private long totalQueueTime = 0;
    private long totalExecutionTime = 0;
    private int maxActiveTasks = 0;
    
    public ManagedExecutorService(String name, int corePoolSize, int maxPoolSize, 
                                 int queueCapacity, long keepAliveTime, TimeUnit unit) {
        this.name = name;
        
        // Create delegate executor with monitoring wrapper
        this.delegate = new ThreadPoolExecutor(
            corePoolSize, maxPoolSize,
            keepAliveTime, unit,
            new MonitoringBlockingQueue<>(queueCapacity),
            new ManagedThreadFactory(name),
            new MonitoringRejectedExecutionHandler()
        ) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                activeTasks.incrementAndGet();
                maxActiveTasks = Math.max(maxActiveTasks, activeTasks.get());
                
                if (r instanceof TrackedRunnable) {
                    ((TrackedRunnable) r).setStartTime(System.currentTimeMillis());
                }
                
                super.beforeExecute(t, r);
            }
            
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                activeTasks.decrementAndGet();
                
                if (r instanceof TrackedRunnable) {
                    TrackedRunnable tr = (TrackedRunnable) r;
                    long executionTime = System.currentTimeMillis() - tr.getStartTime();
                    totalExecutionTime += executionTime;
                    
                    if (t != null) {
                        failedTasks.incrementAndGet();
                    } else {
                        completedTasks.incrementAndGet();
                    }
                }
                
                super.afterExecute(r, t);
            }
        };
        
        System.out.printf("Created ManagedExecutorService: %s [core=%d, max=%d, queue=%d]%n",
            name, corePoolSize, maxPoolSize, queueCapacity);
    }
    
    @Override
    public void execute(Runnable command) {
        checkShutdown();
        delegate.execute(wrapRunnable(command));
    }
    
    @Override
    public Future<?> submit(Runnable task) {
        checkShutdown();
        return delegate.submit(wrapRunnable(task));
    }
    
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        checkShutdown();
        return delegate.submit(wrapRunnable(task), result);
    }
    
    @Override
    public <T> Future<T> submit(Callable<T> task) {
        checkShutdown();
        return delegate.submit(wrapCallable(task));
    }
    
    private Runnable wrapRunnable(Runnable r) {
        if (r instanceof TrackedRunnable) {
            return r;
        }
        return new TrackedRunnable(r);
    }
    
    private <T> Callable<T> wrapCallable(Callable<T> c) {
        if (c instanceof TrackedCallable) {
            return c;
        }
        return new TrackedCallable<>(c);
    }
    
    private void checkShutdown() {
        if (shuttingDown) {
            throw new RejectedExecutionException("Executor " + name + " is shutting down");
        }
    }
    
    /**
     * Graceful shutdown with timeout
     */
    @Override
    public void shutdown() {
        shutdownLock.lock();
        try {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            
            System.out.printf("Shutting down executor: %s%n", name);
            
            // Run shutdown hooks
            for (Runnable hook : shutdownHooks) {
                try {
                    hook.run();
                } catch (Exception e) {
                    System.err.printf("Error in shutdown hook for %s: %s%n", name, e.getMessage());
                }
            }
            
            delegate.shutdown();
            
        } finally {
            shutdownLock.unlock();
        }
    }
    
    @Override
    public List<Runnable> shutdownNow() {
        shutdownLock.lock();
        try {
            shuttingDown = true;
            System.out.printf("Forcing shutdown of executor: %s%n", name);
            return delegate.shutdownNow();
        } finally {
            shutdownLock.unlock();
        }
    }
    
    @Override
    public boolean isShutdown() {
        return shuttingDown || delegate.isShutdown();
    }
    
    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }
    
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
    
    /**
     * Graceful shutdown with progress reporting
     */
    public boolean gracefulShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        shutdown();
        
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        long checkInterval = 1000; // 1 second
        
        while (System.currentTimeMillis() < deadline) {
            if (isTerminated()) {
                return true;
            }
            
            // Report progress
            System.out.printf("[%s] Waiting for termination: %d active, %d queue%n",
                name, activeTasks.get(), getQueueSize());
            
            Thread.sleep(Math.min(checkInterval, deadline - System.currentTimeMillis()));
        }
        
        // Force shutdown if timeout reached
        if (!isTerminated()) {
            System.out.printf("[%s] Timeout reached, forcing shutdown%n", name);
            shutdownNow();
        }
        
        return isTerminated();
    }
    
    /**
     * Add shutdown hook
     */
    public void addShutdownHook(Runnable hook) {
        shutdownHooks.add(hook);
    }
    
    /**
     * Get executor statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("name", name);
        stats.put("activeTasks", activeTasks.get());
        stats.put("completedTasks", completedTasks.get());
        stats.put("failedTasks", failedTasks.get());
        stats.put("maxActiveTasks", maxActiveTasks);
        stats.put("queueSize", getQueueSize());
        stats.put("uptimeMinutes", (System.currentTimeMillis() - startTime) / 60000.0);
        
        if (completedTasks.get() > 0) {
            stats.put("avgExecutionTimeMs", totalExecutionTime / (double) completedTasks.get());
        }
        
        if (delegate instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) delegate;
            stats.put("poolSize", tpe.getPoolSize());
            stats.put("corePoolSize", tpe.getCorePoolSize());
            stats.put("maxPoolSize", tpe.getMaximumPoolSize());
            stats.put("largestPoolSize", tpe.getLargestPoolSize());
        }
        
        return stats;
    }
    
    private int getQueueSize() {
        if (delegate instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) delegate).getQueue().size();
        }
        return 0;
    }
    
    // Delegate methods
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        checkShutdown();
        return delegate.invokeAll(wrapCallables(tasks));
    }
    
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) 
            throws InterruptedException {
        checkShutdown();
        return delegate.invokeAll(wrapCallables(tasks), timeout, unit);
    }
    
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) 
            throws InterruptedException, ExecutionException {
        checkShutdown();
        return delegate.invokeAny(wrapCallables(tasks));
    }
    
    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) 
            throws InterruptedException, ExecutionException, TimeoutException {
        checkShutdown();
        return delegate.invokeAny(wrapCallables(tasks), timeout, unit);
    }
    
    private <T> Collection<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<>();
        for (Callable<T> task : tasks) {
            wrapped.add(wrapCallable(task));
        }
        return wrapped;
    }
    
    // Inner classes
    private class TrackedRunnable implements Runnable {
        private final Runnable delegate;
        private long startTime;
        private long queueTime;
        
        TrackedRunnable(Runnable delegate) {
            this.delegate = delegate;
            this.queueTime = System.currentTimeMillis();
        }
        
        void setStartTime(long startTime) {
            this.startTime = startTime;
            totalQueueTime += (startTime - queueTime);
        }
        
        long getStartTime() {
            return startTime;
        }
        
        @Override
        public void run() {
            delegate.run();
        }
    }
    
    private class TrackedCallable<T> implements Callable<T> {
        private final Callable<T> delegate;
        private long startTime;
        private long queueTime;
        
        TrackedCallable(Callable<T> delegate) {
            this.delegate = delegate;
            this.queueTime = System.currentTimeMillis();
        }
        
        void setStartTime(long startTime) {
            this.startTime = startTime;
            totalQueueTime += (startTime - queueTime);
        }
        
        long getStartTime() {
            return startTime;
        }
        
        @Override
        public T call() throws Exception {
            return delegate.call();
        }
    }
    
    private class MonitoringBlockingQueue<E> extends LinkedBlockingQueue<E> {
        private final AtomicInteger maxSize = new AtomicInteger(0);
        
        MonitoringBlockingQueue(int capacity) {
            super(capacity);
        }
        
        @Override
        public boolean offer(E e) {
            boolean offered = super.offer(e);
            maxSize.set(Math.max(maxSize.get(), size()));
            return offered;
        }
    }
    
    private class ManagedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        
        ManagedThreadFactory(String poolName) {
            namePrefix = poolName + "-worker-";
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            
            // Set uncaught exception handler
            t.setUncaughtExceptionHandler((thread, throwable) -> {
                System.err.printf("Uncaught exception in thread %s: %s%n", 
                    thread.getName(), throwable.getMessage());
                throwable.printStackTrace();
            });
            
            return t;
        }
    }
    
    private class MonitoringRejectedExecutionHandler implements RejectedExecutionHandler {
        private final AtomicInteger rejectedCount = new AtomicInteger(0);
        
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedCount.incrementAndGet();
            System.err.printf("[%s] Task rejected (queue full). Rejected count: %d%n", 
                name, rejectedCount.get());
            
            // Try to add to stats
            Map<String, Object> stats = getStatistics();
            System.err.println("Executor state: " + stats);
            
            throw new RejectedExecutionException("Task rejected from " + name);
        }
    }
}
