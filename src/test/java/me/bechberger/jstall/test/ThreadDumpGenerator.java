package me.bechberger.jstall.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Framework for generating and caching real-world thread dumps.
 * Generates thread dumps once and stores them in test resources for reuse across tests.
 */
public class ThreadDumpGenerator {

    private static final Path RESOURCES_DIR = Paths.get("src/test/resources");

    static {
        try {
            Files.createDirectories(RESOURCES_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create resources directory", e);
        }
    }

    /**
     * Get or generate a thread dump with the specified scenario.
     * Checks test resources first, generates if missing.
     */
    public static Path getOrGenerateThreadDump(String scenarioName, ThreadDumpScenario scenario) throws IOException {
        Path resourceFile = RESOURCES_DIR.resolve(scenarioName + ".txt");

        if (Files.exists(resourceFile)) {
            return resourceFile;
        }

        // Generate the thread dump and save to resources
        String threadDump = scenario.generate();
        Files.writeString(resourceFile, threadDump);

        return resourceFile;
    }

    /**
     * Get or generate multiple thread dumps with the specified scenario.
     * Useful for analyzing thread dump diffs over time.
     */
    public static List<Path> getOrGenerateThreadDumps(String scenarioName, MultiDumpScenario scenario, int count) throws IOException {
        List<Path> resourceFiles = new ArrayList<>();
        boolean allExist = true;

        for (int i = 0; i < count; i++) {
            Path resourceFile = RESOURCES_DIR.resolve(scenarioName + "-" + i + ".txt");
            resourceFiles.add(resourceFile);
            if (!Files.exists(resourceFile)) {
                allExist = false;
            }
        }

        if (allExist) {
            return resourceFiles;
        }

        // Generate the thread dumps and save to resources
        List<String> threadDumps = scenario.generate(count);
        for (int i = 0; i < count; i++) {
            Files.writeString(resourceFiles.get(i), threadDumps.get(i));
        }

        return resourceFiles;
    }

    /**
     * Force regeneration of a thread dump
     */
    public static Path regenerateThreadDump(String scenarioName, ThreadDumpScenario scenario) throws IOException {
        Path resourceFile = RESOURCES_DIR.resolve(scenarioName + ".txt");
        String threadDump = scenario.generate();
        Files.writeString(resourceFile, threadDump);
        return resourceFile;
    }

    /**
     * Clear all cached thread dumps from test resources
     */
    public static void clearCache() throws IOException {
        if (Files.exists(RESOURCES_DIR)) {
            Files.walk(RESOURCES_DIR)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .filter(p -> !p.getFileName().toString().startsWith("thread-dump-")) // Keep original test files
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    @FunctionalInterface
    public interface ThreadDumpScenario {
        String generate() throws IOException;
    }

    @FunctionalInterface
    public interface MultiDumpScenario {
        List<String> generate(int count) throws IOException;
    }

    /**
     * Generate a thread dump with deadlocked threads
     */
    public static ThreadDumpScenario deadlockScenario() {
        return () -> {
            Object lock1 = new Object();
            Object lock2 = new Object();
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch deadlockReached = new CountDownLatch(2);

            Thread thread1 = new Thread(() -> {
                synchronized (lock1) {
                    bothStarted.countDown();
                    try {
                        bothStarted.await();
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock2) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-A");

            Thread thread2 = new Thread(() -> {
                synchronized (lock2) {
                    bothStarted.countDown();
                    try {
                        bothStarted.await();
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock1) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-B");

            thread1.start();
            thread2.start();

            try {
                deadlockReached.await(2, TimeUnit.SECONDS);
                Thread.sleep(100); // Let deadlock settle
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for deadlock", e);
            } finally {
                thread1.interrupt();
                thread2.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with ReentrantLock usage
     */
    public static ThreadDumpScenario reentrantLockScenario() {
        return () -> {
            ReentrantLock lock = new ReentrantLock();
            CountDownLatch lockHeld = new CountDownLatch(1);
            CountDownLatch waitingStarted = new CountDownLatch(1);

            Thread holder = new Thread(() -> {
                lock.lock();
                try {
                    lockHeld.countDown();
                    try {
                        Thread.sleep(5000); // Hold lock
                    } catch (InterruptedException e) {
                        // Exit
                    }
                } finally {
                    lock.unlock();
                }
            }, "LockHolder");

            Thread waiter = new Thread(() -> {
                try {
                    lockHeld.await();
                    Thread.sleep(50); // Ensure holder is deep in sleep
                    waitingStarted.countDown();
                    lock.lock();
                    lock.unlock();
                } catch (InterruptedException e) {
                    // Exit
                }
            }, "LockWaiter");

            holder.start();
            waiter.start();

            try {
                waitingStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for lock scenario", e);
            } finally {
                holder.interrupt();
                waiter.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with virtual threads (Java 21+)
     */
    public static ThreadDumpScenario virtualThreadScenario() {
        return () -> {
            CountDownLatch virtualStarted = new CountDownLatch(3);
            Object monitor = new Object();

            Thread[] virtualThreads = new Thread[3];
            for (int i = 0; i < 3; i++) {
                final int index = i;
                virtualThreads[i] = Thread.ofVirtual().name("VirtualWorker-" + i).start(() -> {
                    virtualStarted.countDown();
                    try {
                        if (index == 0) {
                            // Blocking I/O simulation
                            Thread.sleep(10000);
                        } else if (index == 1) {
                            // Waiting on monitor
                            synchronized (monitor) {
                                monitor.wait();
                            }
                        } else {
                            // Just running
                            while (!Thread.currentThread().isInterrupted()) {
                                Thread.sleep(100);
                            }
                        }
                    } catch (InterruptedException e) {
                        // Exit
                    }
                });
            }

            try {
                virtualStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for virtual threads", e);
            } finally {
                for (Thread vt : virtualThreads) {
                    vt.interrupt();
                }
            }
        };
    }

    /**
     * Generate a complex multi-scenario thread dump
     */
    public static ThreadDumpScenario complexScenario() {
        return () -> {
            Object lock1 = new Object();
            Object lock2 = new Object();
            ReentrantLock reentrantLock = new ReentrantLock();
            CountDownLatch allStarted = new CountDownLatch(5);

            // Platform thread - runnable
            Thread platformRunnable = new Thread(() -> {
                allStarted.countDown();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Math.sqrt(Math.random());
                    }
                } catch (Exception e) {
                    // Exit
                }
            }, "PlatformWorker");
            platformRunnable.setPriority(7);

            // Platform thread - waiting
            Thread platformWaiting = new Thread(() -> {
                allStarted.countDown();
                synchronized (lock1) {
                    try {
                        lock1.wait();
                    } catch (InterruptedException e) {
                        // Exit
                    }
                }
            }, "WaitingThread");
            platformWaiting.setDaemon(true);

            // Platform thread - blocked
            Thread platformBlocked = new Thread(() -> {
                allStarted.countDown();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Continue
                }
                synchronized (lock2) {
                    // Will block
                }
            }, "BlockedThread");

            // Platform thread holding lock
            Thread lockHolder = new Thread(() -> {
                synchronized (lock2) {
                    allStarted.countDown();
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        // Exit
                    }
                }
            }, "LockHolderThread");

            // Virtual thread
            Thread virtualThread = Thread.ofVirtual().name("VirtualTask").start(() -> {
                allStarted.countDown();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // Exit
                }
            });

            platformRunnable.start();
            platformWaiting.start();
            lockHolder.start();
            platformBlocked.start();

            try {
                allStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(200); // Let scenario settle
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for complex scenario", e);
            } finally {
                platformRunnable.interrupt();
                platformWaiting.interrupt();
                platformBlocked.interrupt();
                lockHolder.interrupt();
                virtualThread.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with a thread pool and many worker threads
     */
    public static ThreadDumpScenario threadPoolScenario() {
        return () -> {
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch allStarted = new CountDownLatch(20);
            CountDownLatch blockingLatch = new CountDownLatch(1);

            try {
                // Submit 20 tasks to a pool of 10 threads
                for (int i = 0; i < 20; i++) {
                    final int taskId = i;
                    executor.submit(() -> {
                        Thread.currentThread().setName("Worker-" + taskId);
                        allStarted.countDown();
                        try {
                            // Some threads compute, others wait
                            if (taskId % 2 == 0) {
                                blockingLatch.await();
                            } else {
                                // CPU intensive work
                                for (int j = 0; j < 1000000 && !Thread.currentThread().isInterrupted(); j++) {
                                    Math.sqrt(j);
                                }
                            }
                        } catch (InterruptedException e) {
                            // Exit
                        }
                    });
                }

                allStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for thread pool scenario", e);
            } finally {
                blockingLatch.countDown();
                executor.shutdownNow();
            }
        };
    }

    /**
     * Generate a thread dump with many virtual threads (50+)
     */
    public static ThreadDumpScenario manyVirtualThreadsScenario() {
        return () -> {
            List<Thread> virtualThreads = new ArrayList<>();
            CountDownLatch allStarted = new CountDownLatch(50);
            Object[] locks = new Object[5];
            for (int i = 0; i < 5; i++) {
                locks[i] = new Object();
            }

            try {
                for (int i = 0; i < 50; i++) {
                    final int threadId = i;
                    Thread vt = Thread.ofVirtual().name("VirtualWorker-" + threadId).start(() -> {
                        allStarted.countDown();
                        try {
                            // Different behaviors based on thread id
                            int behavior = threadId % 5;
                            switch (behavior) {
                                case 0 -> Thread.sleep(10000); // Sleeping
                                case 1 -> { // Waiting on monitor
                                    synchronized (locks[0]) {
                                        locks[0].wait();
                                    }
                                }
                                case 2 -> { // CPU work
                                    while (!Thread.currentThread().isInterrupted()) {
                                        Math.sqrt(Math.random());
                                    }
                                }
                                case 3 -> { // Blocking on synchronized
                                    synchronized (locks[threadId % locks.length]) {
                                        Thread.sleep(10000);
                                    }
                                }
                                case 4 -> { // Timed wait
                                    synchronized (locks[2]) {
                                        locks[2].wait(30000);
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            // Exit
                        }
                    });
                    virtualThreads.add(vt);
                }

                allStarted.await(3, TimeUnit.SECONDS);
                Thread.sleep(200);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for virtual threads", e);
            } finally {
                virtualThreads.forEach(Thread::interrupt);
            }
        };
    }

    /**
     * Generate a thread dump with minimal threads (just a few)
     */
    public static ThreadDumpScenario minimalThreadsScenario() {
        return () -> {
            CountDownLatch allStarted = new CountDownLatch(2);

            Thread worker1 = new Thread(() -> {
                allStarted.countDown();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // Exit
                }
            }, "SimpleWorker-1");

            Thread worker2 = new Thread(() -> {
                allStarted.countDown();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Math.sqrt(Math.random());
                    }
                } catch (Exception e) {
                    // Exit
                }
            }, "SimpleWorker-2");

            worker1.start();
            worker2.start();

            try {
                allStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for minimal scenario", e);
            } finally {
                worker1.interrupt();
                worker2.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with a three-way deadlock
     */
    public static ThreadDumpScenario threeWayDeadlockScenario() {
        return () -> {
            Object lockA = new Object();
            Object lockB = new Object();
            Object lockC = new Object();
            CountDownLatch allStarted = new CountDownLatch(3);
            CountDownLatch deadlockReached = new CountDownLatch(3);

            Thread threadA = new Thread(() -> {
                synchronized (lockA) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lockB) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-A");

            Thread threadB = new Thread(() -> {
                synchronized (lockB) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lockC) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-B");

            Thread threadC = new Thread(() -> {
                synchronized (lockC) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lockA) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-C");

            threadA.start();
            threadB.start();
            threadC.start();

            try {
                deadlockReached.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for three-way deadlock", e);
            } finally {
                threadA.interrupt();
                threadB.interrupt();
                threadC.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with multiple separate deadlock cycles
     * Creates 2 independent deadlocks: A-B and C-D
     */
    public static ThreadDumpScenario multiDeadlockScenario() {
        return () -> {
            // First deadlock pair
            Object lock1 = new Object();
            Object lock2 = new Object();
            // Second deadlock pair
            Object lock3 = new Object();
            Object lock4 = new Object();

            CountDownLatch allStarted = new CountDownLatch(5); // 4 deadlocked + 1 worker
            CountDownLatch deadlockReached = new CountDownLatch(4);

            // First deadlock: A waits for B, B waits for A
            Thread threadA = new Thread(() -> {
                synchronized (lock1) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock2) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-A");

            Thread threadB = new Thread(() -> {
                synchronized (lock2) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock1) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-B");

            // Second deadlock: C waits for D, D waits for C
            Thread threadC = new Thread(() -> {
                synchronized (lock3) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock4) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-C");

            Thread threadD = new Thread(() -> {
                synchronized (lock4) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock3) {
                        // Never reached
                    }
                }
            }, "DeadlockThread-D");

            // Non-deadlocked worker thread
            Thread worker = new Thread(() -> {
                allStarted.countDown();
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Math.sqrt(Math.random());
                        Thread.sleep(10);
                    }
                } catch (InterruptedException e) {
                    // Exit
                }
            }, "WorkerThread-1");

            threadA.start();
            threadB.start();
            threadC.start();
            threadD.start();
            worker.start();

            try {
                deadlockReached.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for multi-deadlock", e);
            } finally {
                threadA.interrupt();
                threadB.interrupt();
                threadC.interrupt();
                threadD.interrupt();
                worker.interrupt();
            }
        };
    }

    /**
     * Generate a thread dump with read-write lock contention
     */
    public static ThreadDumpScenario readWriteLockScenario() {
        return () -> {
            ReadWriteLock rwLock = new ReentrantReadWriteLock();
            CountDownLatch allStarted = new CountDownLatch(6);
            CountDownLatch lockHeld = new CountDownLatch(1);

            List<Thread> threads = new ArrayList<>();

            // One writer holding the lock
            Thread writer = new Thread(() -> {
                rwLock.writeLock().lock();
                try {
                    lockHeld.countDown();
                    allStarted.countDown();
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // Exit
                } finally {
                    rwLock.writeLock().unlock();
                }
            }, "WriteLockHolder");
            threads.add(writer);
            writer.start();

            try {
                lockHeld.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);

                // Multiple readers waiting
                for (int i = 0; i < 3; i++) {
                    final int readerId = i;
                    Thread reader = new Thread(() -> {
                        allStarted.countDown();
                        rwLock.readLock().lock();
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            // Exit
                        } finally {
                            rwLock.readLock().unlock();
                        }
                    }, "ReadLockWaiter-" + readerId);
                    threads.add(reader);
                    reader.start();
                }

                // Another writer waiting
                for (int i = 0; i < 2; i++) {
                    final int writerId = i;
                    Thread anotherWriter = new Thread(() -> {
                        allStarted.countDown();
                        rwLock.writeLock().lock();
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            // Exit
                        } finally {
                            rwLock.writeLock().unlock();
                        }
                    }, "WriteLockWaiter-" + writerId);
                    threads.add(anotherWriter);
                    anotherWriter.start();
                }

                allStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(200);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for read-write lock scenario", e);
            } finally {
                threads.forEach(Thread::interrupt);
            }
        };
    }

    /**
     * Generate a thread dump simulating GC activity with high memory allocation
     */
    public static ThreadDumpScenario gcActivityScenario() {
        return () -> {
            CountDownLatch allStarted = new CountDownLatch(8);
            List<Thread> threads = new ArrayList<>();

            // Threads doing heavy allocation
            for (int i = 0; i < 8; i++) {
                final int threadId = i;
                Thread allocator = new Thread(() -> {
                    allStarted.countDown();
                    List<byte[]> garbage = new ArrayList<>();
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            // Allocate 1MB chunks
                            garbage.add(new byte[1024 * 1024]);
                            // Keep only last 100 chunks
                            if (garbage.size() > 100) {
                                garbage.remove(0);
                            }
                            // Small sleep to not completely thrash
                            if (threadId % 2 == 0) {
                                Thread.sleep(1);
                            }
                        }
                    } catch (InterruptedException e) {
                        // Exit
                    }
                }, "MemoryAllocator-" + threadId);
                threads.add(allocator);
                allocator.start();
            }

            try {
                allStarted.await(2, TimeUnit.SECONDS);
                Thread.sleep(500); // Let some GC happen
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for GC scenario", e);
            } finally {
                threads.forEach(Thread::interrupt);
            }
        };
    }

    /**
     * Generate a thread dump with virtual threads in deadlock (Java 21+)
     * Creates a deadlock scenario using virtual threads to test parsing of virtual thread deadlocks
     */
    public static ThreadDumpScenario virtualThreadDeadlockScenario() {
        return () -> {
            Object lock1 = new Object();
            Object lock2 = new Object();
            CountDownLatch allStarted = new CountDownLatch(2);
            CountDownLatch deadlockReached = new CountDownLatch(2);

            // Virtual thread A - acquires lock1, waits for lock2
            Thread vtA = Thread.ofVirtual().name("VirtualDeadlock-A").start(() -> {
                synchronized (lock1) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock2) {
                        // Never reached
                    }
                }
            });

            // Virtual thread B - acquires lock2, waits for lock1
            Thread vtB = Thread.ofVirtual().name("VirtualDeadlock-B").start(() -> {
                synchronized (lock2) {
                    allStarted.countDown();
                    try {
                        allStarted.await();
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    deadlockReached.countDown();
                    synchronized (lock1) {
                        // Never reached
                    }
                }
            });

            // Also add some regular virtual threads doing work
            Thread vtWorker1 = Thread.ofVirtual().name("VirtualWorker-1").start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    // Exit
                }
            });

            Thread vtWorker2 = Thread.ofVirtual().name("VirtualWorker-2").start(() -> {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // Exit
                }
            });

            try {
                deadlockReached.await(2, TimeUnit.SECONDS);
                Thread.sleep(100);
                return captureThreadDump();
            } catch (InterruptedException e) {
                throw new IOException("Interrupted waiting for virtual thread deadlock", e);
            } finally {
                vtA.interrupt();
                vtB.interrupt();
                vtWorker1.interrupt();
                vtWorker2.interrupt();
            }
        };
    }

    /**
     * Multi-dump scenario: Thread pool with increasing load
     */
    public static MultiDumpScenario threadPoolLoadIncrease() {
        return (count) -> {
            List<String> dumps = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(20);
            AtomicInteger tasksSubmitted = new AtomicInteger(0);

            try {
                for (int dumpIdx = 0; dumpIdx < count; dumpIdx++) {
                    int tasksToSubmit = 5 + (dumpIdx * 10); // Increasing load
                    CountDownLatch tasksStarted = new CountDownLatch(tasksToSubmit);
                    CountDownLatch holdLatch = new CountDownLatch(1);

                    for (int i = 0; i < tasksToSubmit; i++) {
                        final int taskId = tasksSubmitted.incrementAndGet();
                        executor.submit(() -> {
                            Thread.currentThread().setName("PoolWorker-" + taskId);
                            tasksStarted.countDown();
                            try {
                                holdLatch.await();
                            } catch (InterruptedException e) {
                                // Exit
                            }
                        });
                    }

                    tasksStarted.await(2, TimeUnit.SECONDS);
                    Thread.sleep(100);
                    dumps.add(captureThreadDump());
                    holdLatch.countDown();
                    Thread.sleep(200); // Let tasks complete
                }

                return dumps;
            } catch (Exception e) {
                throw new IOException("Error generating thread pool load dumps", e);
            } finally {
                executor.shutdownNow();
            }
        };
    }

    /**
     * Multi-dump scenario: Virtual threads spawning over time
     */
    public static MultiDumpScenario virtualThreadsOverTime() {
        return (count) -> {
            List<String> dumps = new ArrayList<>();
            List<Thread> allVirtualThreads = new ArrayList<>();

            try {
                for (int dumpIdx = 0; dumpIdx < count; dumpIdx++) {
                    int threadsToCreate = 10 + (dumpIdx * 5);
                    CountDownLatch started = new CountDownLatch(threadsToCreate);

                    for (int i = 0; i < threadsToCreate; i++) {
                        final int threadId = allVirtualThreads.size();
                        Thread vt = Thread.ofVirtual().name("VirtualThread-" + threadId).start(() -> {
                            started.countDown();
                            try {
                                Thread.sleep(30000);
                            } catch (InterruptedException e) {
                                // Exit
                            }
                        });
                        allVirtualThreads.add(vt);
                    }

                    started.await(2, TimeUnit.SECONDS);
                    Thread.sleep(100);
                    dumps.add(captureThreadDump());
                    Thread.sleep(100);
                }

                return dumps;
            } catch (Exception e) {
                throw new IOException("Error generating virtual threads over time dumps", e);
            } finally {
                allVirtualThreads.forEach(Thread::interrupt);
            }
        };
    }

    /**
     * Multi-dump scenario: Deadlock forming over time
     */
    public static MultiDumpScenario deadlockFormation() {
        return (count) -> {
            List<String> dumps = new ArrayList<>();
            Object lock1 = new Object();
            Object lock2 = new Object();
            CountDownLatch thread1Ready = new CountDownLatch(1);
            CountDownLatch thread2Ready = new CountDownLatch(1);
            CountDownLatch proceedToDeadlock = new CountDownLatch(1);

            Thread thread1 = new Thread(() -> {
                synchronized (lock1) {
                    thread1Ready.countDown();
                    try {
                        proceedToDeadlock.await();
                        Thread.sleep(50);
                        synchronized (lock2) {
                            // Never reached
                        }
                    } catch (InterruptedException e) {
                        // Exit
                    }
                }
            }, "DeadlockCandidate-A");

            Thread thread2 = new Thread(() -> {
                synchronized (lock2) {
                    thread2Ready.countDown();
                    try {
                        proceedToDeadlock.await();
                        Thread.sleep(50);
                        synchronized (lock1) {
                            // Never reached
                        }
                    } catch (InterruptedException e) {
                        // Exit
                    }
                }
            }, "DeadlockCandidate-B");

            try {
                thread1.start();
                thread2.start();

                thread1Ready.await(1, TimeUnit.SECONDS);
                thread2Ready.await(1, TimeUnit.SECONDS);

                for (int i = 0; i < count; i++) {
                    if (i == count / 2) {
                        // Trigger deadlock halfway through
                        proceedToDeadlock.countDown();
                    }
                    Thread.sleep(100);
                    dumps.add(captureThreadDump());
                }

                return dumps;
            } catch (Exception e) {
                throw new IOException("Error generating deadlock formation dumps", e);
            } finally {
                thread1.interrupt();
                thread2.interrupt();
            }
        };
    }

    /**
     * Multi-dump scenario: GC activity with increasing memory pressure
     */
    public static MultiDumpScenario gcActivityOverTime() {
        return (count) -> {
            List<String> dumps = new ArrayList<>();
            List<Thread> allocators = new ArrayList<>();

            try {
                for (int dumpIdx = 0; dumpIdx < count; dumpIdx++) {
                    int threadsToAdd = 2;
                    CountDownLatch started = new CountDownLatch(threadsToAdd);

                    for (int i = 0; i < threadsToAdd; i++) {
                        final int threadId = allocators.size();
                        Thread allocator = new Thread(() -> {
                            started.countDown();
                            List<byte[]> garbage = new ArrayList<>();
                            try {
                                while (!Thread.currentThread().isInterrupted()) {
                                    garbage.add(new byte[512 * 1024]); // 512KB chunks
                                    if (garbage.size() > 50) {
                                        garbage.remove(0);
                                    }
                                    Thread.sleep(5);
                                }
                            } catch (InterruptedException e) {
                                // Exit
                            }
                        }, "Allocator-" + threadId);
                        allocators.add(allocator);
                        allocator.start();
                    }

                    started.await(1, TimeUnit.SECONDS);
                    Thread.sleep(200); // Let allocation happen
                    dumps.add(captureThreadDump());
                }

                return dumps;
            } catch (Exception e) {
                throw new IOException("Error generating GC activity dumps", e);
            } finally {
                allocators.forEach(Thread::interrupt);
            }
        };
    }

    /**
     * Capture current thread dump using jstack
     */
    public static String captureThreadDump() throws IOException {
        try {
            long pid = ProcessHandle.current().pid();

            // Try jstack command with -l for extended lock information
            ProcessBuilder pb = new ProcessBuilder("jstack", "-l", String.valueOf(pid));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode == 0 && !output.isEmpty() && !output.contains("Unable to open socket file")) {
                return output;
            }

            // If jstack failed, try with -F (force)
            if (exitCode != 0) {
                pb = new ProcessBuilder("jstack", "-F", String.valueOf(pid));
                pb.redirectErrorStream(true);
                process = pb.start();

                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()))) {
                    output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                }

                exitCode = process.waitFor();
                if (exitCode == 0 && !output.isEmpty()) {
                    return output;
                }
            }

            // Fallback to Thread.getAllStackTraces() if jstack is not available
            System.err.println("jstack not available or failed, using fallback method");
            return captureThreadDumpFallback();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing thread dump", e);
        } catch (IOException e) {
            // Fallback if jstack is not available
            System.err.println("jstack not available: " + e.getMessage() + ", using fallback method");
            return captureThreadDumpFallback();
        }
    }

    /**
     * Fallback method using Thread.getAllStackTraces()
     */
    private static String captureThreadDumpFallback() {
        throw new UnsupportedOperationException("Fallback thread dump capture not supported");
    }
}