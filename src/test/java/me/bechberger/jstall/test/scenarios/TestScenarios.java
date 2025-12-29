package me.bechberger.jstall.test.scenarios;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Collection of predefined test scenarios for JFR testing.
 */
public class TestScenarios {

    /**
     * CPU-intensive scenario with hot methods
     */
    public static ScenarioDefinition cpuHotspot() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "cpu-hotspot";
            }

            @Override
            public String getDescription() {
                return "CPU-intensive work with identifiable hot methods";
            }

            @Override
            public void run() {
                int numThreads = 4;
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);

                for (int i = 0; i < numThreads; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            hotMethod();
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            private void hotMethod() {
                // CPU-intensive work
                double result = 0;
                for (int i = 0; i < 10000; i++) {
                    result += Math.sin(i) * Math.cos(i);
                }
                // Prevent optimization
                if (result == Double.MAX_VALUE) System.out.println(result);
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectHighCPU()
                        .minThreadCount(4)
                        .build();
            }
        };
    }

    /**
     * Lock contention scenario
     */
    public static ScenarioDefinition lockContention() {
        return new ScenarioDefinition() {
            private final Object hotLock = new Object();

            @Override
            public String getName() {
                return "lock-contention";
            }

            @Override
            public String getDescription() {
                return "Multiple threads contending for the same lock";
            }

            @Override
            public void run() {
                int numThreads = 8;
                ExecutorService executor = Executors.newFixedThreadPool(numThreads);

                for (int i = 0; i < numThreads; i++) {
                    int threadNum = i;
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            synchronized (hotLock) {
                                // Hold lock for a bit
                                try {
                                    Thread.sleep(10);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            // Small delay before trying again
                            Thread.yield();
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectLockContention()
                        .minThreadCount(8)
                        .build();
            }
        };
    }

    /**
     * File I/O scenario
     */
    public static ScenarioDefinition fileIO() {
        return new ScenarioDefinition() {
            private List<File> tempFiles = new ArrayList<>();

            @Override
            public String getName() {
                return "file-io";
            }

            @Override
            public String getDescription() {
                return "File read/write operations";
            }

            @Override
            public void setup() {
                try {
                    for (int i = 0; i < 5; i++) {
                        File f = File.createTempFile("jstall-test-", ".txt");
                        f.deleteOnExit();
                        // Write some data
                        try (FileWriter fw = new FileWriter(f)) {
                            for (int j = 0; j < 10000; j++) {
                                fw.write("Line " + j + " with some test data\n");
                            }
                        }
                        tempFiles.add(f);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(4);

                for (int i = 0; i < 4; i++) {
                    int threadNum = i;
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            for (File f : tempFiles) {
                                try {
                                    // Read file
                                    try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                                        String line;
                                        while ((line = br.readLine()) != null) {
                                            // Process line
                                            if (line.isEmpty()) break;
                                        }
                                    }
                                    // Write to temp file
                                    File tempOut = File.createTempFile("jstall-out-", ".txt");
                                    tempOut.deleteOnExit();
                                    try (FileWriter fw = new FileWriter(tempOut)) {
                                        fw.write("Output from thread " + threadNum + "\n");
                                    }
                                    tempOut.delete();
                                } catch (IOException e) {
                                    // Ignore
                                }
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public void cleanup() {
                for (File f : tempFiles) {
                    f.delete();
                }
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectIOBlocking()
                        .build();
            }
        };
    }

    /**
     * Socket I/O scenario
     */
    public static ScenarioDefinition socketIO() {
        return new ScenarioDefinition() {
            private ServerSocket serverSocket;
            private volatile boolean running = true;

            @Override
            public String getName() {
                return "socket-io";
            }

            @Override
            public String getDescription() {
                return "Socket read/write operations";
            }

            @Override
            public void setup() {
                try {
                    serverSocket = new ServerSocket(0); // Random available port
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void run() {
                int port = serverSocket.getLocalPort();
                ExecutorService executor = Executors.newFixedThreadPool(6);

                // Server threads
                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        while (running && !Thread.interrupted()) {
                            try {
                                Socket client = serverSocket.accept();
                                try (BufferedReader br = new BufferedReader(
                                        new InputStreamReader(client.getInputStream()));
                                     PrintWriter pw = new PrintWriter(client.getOutputStream(), true)) {
                                    String line;
                                    while ((line = br.readLine()) != null) {
                                        pw.println("Echo: " + line);
                                    }
                                }
                            } catch (IOException e) {
                                // Server socket closed
                            }
                        }
                    });
                }

                // Client threads
                for (int i = 0; i < 4; i++) {
                    int clientNum = i;
                    executor.submit(() -> {
                        while (running && !Thread.interrupted()) {
                            try (Socket socket = new Socket("localhost", port);
                                 PrintWriter pw = new PrintWriter(socket.getOutputStream(), true);
                                 BufferedReader br = new BufferedReader(
                                         new InputStreamReader(socket.getInputStream()))) {
                                for (int j = 0; j < 100; j++) {
                                    pw.println("Message " + j + " from client " + clientNum);
                                    String response = br.readLine();
                                    if (response == null) break;
                                }
                            } catch (IOException e) {
                                // Connection issues
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                running = false;
                executor.shutdownNow();
            }

            @Override
            public void cleanup() {
                running = false;
                try {
                    if (serverSocket != null) {
                        serverSocket.close();
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectIOBlocking()
                        .minThreadCount(6)
                        .build();
            }
        };
    }

    /**
     * High allocation scenario
     */
    public static ScenarioDefinition highAllocation() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "high-allocation";
            }

            @Override
            public String getDescription() {
                return "High object allocation rate";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(4);

                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        List<byte[]> buffers = new ArrayList<>();
                        while (!Thread.interrupted()) {
                            // Allocate arrays
                            for (int j = 0; j < 100; j++) {
                                buffers.add(new byte[1024]);
                            }
                            // Clear to allow GC
                            if (buffers.size() > 10000) {
                                buffers.clear();
                            }
                            Thread.yield();
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectHighAllocation()
                        .build();
            }
        };
    }

    /**
     * Thread pool exhaustion scenario
     */
    public static ScenarioDefinition threadPoolExhaustion() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "thread-pool-exhaustion";
            }

            @Override
            public String getDescription() {
                return "Thread pool with all threads busy";
            }

            @Override
            public void run() {
                // Small pool with long-running tasks
                ExecutorService executor = Executors.newFixedThreadPool(4);

                // Submit tasks that take longer than the recording
                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            // CPU work - keeps thread busy
                            double x = 0;
                            for (int j = 0; j < 100000; j++) {
                                x += Math.random();
                            }
                            if (x == -1) break; // Never happens
                        }
                    });
                }

                // Try to submit more (these will queue up)
                for (int i = 0; i < 10; i++) {
                    executor.submit(() -> {
                        System.out.println("This task may never run");
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .minThreadCount(4)
                        .build();
            }
        };
    }

    /**
     * Mixed workload scenario
     */
    public static ScenarioDefinition mixedWorkload() {
        return new ScenarioDefinition() {
            private final Object lock1 = new Object();
            private final Object lock2 = new Object();

            @Override
            public String getName() {
                return "mixed-workload";
            }

            @Override
            public String getDescription() {
                return "Mixed CPU, I/O, and lock contention";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(8);

                // CPU workers
                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            double result = 0;
                            for (int j = 0; j < 10000; j++) {
                                result += Math.sqrt(j);
                            }
                            if (result == -1) break;
                        }
                    });
                }

                // Lock contention workers
                for (int i = 0; i < 3; i++) {
                    int threadNum = i;
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            synchronized (lock1) {
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                        }
                    });
                }

                // Allocation workers
                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        List<String> list = new ArrayList<>();
                        while (!Thread.interrupted()) {
                            list.add("String " + System.nanoTime());
                            if (list.size() > 1000) list.clear();
                        }
                    });
                }

                // Waiting thread
                executor.submit(() -> {
                    synchronized (lock2) {
                        try {
                            lock2.wait(10000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectHighCPU()
                        .expectLockContention()
                        .expectHighAllocation()
                        .minThreadCount(8)
                        .build();
            }
        };
    }

    /**
     * Class loading scenario
     */
    public static ScenarioDefinition classLoading() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "class-loading";
            }

            @Override
            public String getDescription() {
                return "Dynamic class loading activity";
            }

            @Override
            public void run() {
                // Trigger class loading by using various classes
                List<Class<?>> loadedClasses = new ArrayList<>();

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        // Load various standard library classes
                        loadedClasses.add(Class.forName("java.util.HashMap"));
                        loadedClasses.add(Class.forName("java.util.TreeMap"));
                        loadedClasses.add(Class.forName("java.util.LinkedList"));
                        loadedClasses.add(Class.forName("java.util.concurrent.ConcurrentHashMap"));
                        loadedClasses.add(Class.forName("java.util.stream.Stream"));

                        Thread.sleep(100);
                    } catch (ClassNotFoundException | InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder().build();
            }
        };
    }

    /**
     * Deadlock scenario - two threads with circular lock dependency
     */
    public static ScenarioDefinition deadlock() {
        return new ScenarioDefinition() {
            private final Object lock1 = new Object();
            private final Object lock2 = new Object();
            private volatile boolean ready = false;

            @Override
            public String getName() {
                return "deadlock";
            }

            @Override
            public String getDescription() {
                return "Two threads creating a classic deadlock";
            }

            @Override
            public void run() {
                Thread t1 = new Thread(() -> {
                    synchronized (lock1) {
                        ready = true;
                        // Wait for other thread to grab lock2
                        while (!Thread.interrupted()) {
                            Thread.yield();
                            synchronized (lock2) {
                                break; // Never reached
                            }
                        }
                    }
                }, "DeadlockThread-1");

                Thread t2 = new Thread(() -> {
                    // Wait for thread 1 to grab lock1
                    while (!ready) {
                        Thread.yield();
                    }
                    synchronized (lock2) {
                        synchronized (lock1) {
                            // Never reached
                        }
                    }
                }, "DeadlockThread-2");

                t1.start();
                t2.start();

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                t1.interrupt();
                t2.interrupt();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectDeadlock()
                        .expectLockContention()
                        .minThreadCount(2)
                        .build();
            }
        };
    }

    /**
     * Thread waiting scenario - threads waiting on conditions
     */
    public static ScenarioDefinition threadWaiting() {
        return new ScenarioDefinition() {
            private final Object waitLock = new Object();

            @Override
            public String getName() {
                return "thread-waiting";
            }

            @Override
            public String getDescription() {
                return "Threads in WAITING state";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(5);

                for (int i = 0; i < 5; i++) {
                    executor.submit(() -> {
                        synchronized (waitLock) {
                            try {
                                waitLock.wait(10000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Wake up all waiting threads
                synchronized (waitLock) {
                    waitLock.notifyAll();
                }

                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .minThreadCount(5)
                        .build();
            }
        };
    }

    /**
     * Recursive method calls creating deep stacks
     */
    public static ScenarioDefinition deepRecursion() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "deep-recursion";
            }

            @Override
            public String getDescription() {
                return "Deep recursive method calls";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(2);

                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            try {
                                recursiveMethod(0, 50);
                            } catch (Exception e) {
                                // Continue
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            private void recursiveMethod(int depth, int maxDepth) {
                if (depth >= maxDepth || Thread.interrupted()) {
                    // Do some work at the bottom
                    double result = 0;
                    for (int i = 0; i < 1000; i++) {
                        result += Math.sin(i);
                    }
                    if (result == -1) System.out.println(result);
                    return;
                }
                recursiveMethod(depth + 1, maxDepth);
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectHighCPU()
                        .minThreadCount(2)
                        .build();
            }
        };
    }

    /**
     * Native method calls
     */
    public static ScenarioDefinition nativeMethods() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "native-methods";
            }

            @Override
            public String getDescription() {
                return "Threads making native method calls";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(2);

                for (int i = 0; i < 2; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            // System.currentTimeMillis() and similar are native
                            long time = System.nanoTime();
                            String hash = Integer.toHexString(System.identityHashCode(this));

                            // Sleep is also a native method
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .minThreadCount(2)
                        .build();
            }
        };
    }

    /**
     * Producer-consumer pattern with blocking queue
     */
    public static ScenarioDefinition producerConsumer() {
        return new ScenarioDefinition() {
            @Override
            public String getName() {
                return "producer-consumer";
            }

            @Override
            public String getDescription() {
                return "Producer-consumer pattern with blocking queue";
            }

            @Override
            public void run() {
                BlockingQueue<Integer> queue = new LinkedBlockingQueue<>(10);
                ExecutorService executor = Executors.newFixedThreadPool(6);

                // Producers
                for (int i = 0; i < 3; i++) {
                    int producerId = i;
                    executor.submit(() -> {
                        int count = 0;
                        while (!Thread.interrupted()) {
                            try {
                                queue.put(count++);
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
                }

                // Consumers
                for (int i = 0; i < 3; i++) {
                    int consumerId = i;
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            try {
                                Integer item = queue.take();
                                // Process item
                                double result = Math.sqrt(item);
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectLockContention()
                        .minThreadCount(6)
                        .build();
            }
        };
    }

    /**
     * ReentrantLock with multiple readers and writers
     */
    public static ScenarioDefinition reentrantLockScenario() {
        return new ScenarioDefinition() {
            private final Lock lock = new ReentrantLock();
            private int sharedValue = 0;

            @Override
            public String getName() {
                return "reentrant-lock";
            }

            @Override
            public String getDescription() {
                return "Multiple threads using ReentrantLock";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(6);

                // Writers
                for (int i = 0; i < 3; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            lock.lock();
                            try {
                                sharedValue++;
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            } finally {
                                lock.unlock();
                            }
                        }
                    });
                }

                // Readers
                for (int i = 0; i < 3; i++) {
                    executor.submit(() -> {
                        while (!Thread.interrupted()) {
                            lock.lock();
                            try {
                                int value = sharedValue;
                                Thread.sleep(5);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            } finally {
                                lock.unlock();
                            }
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectLockContention()
                        .minThreadCount(6)
                        .build();
            }
        };
    }

    /**
     * Get all available scenarios
     */
    public static List<ScenarioDefinition> all() {
        return List.of(
                cpuHotspot(),
                lockContention(),
                fileIO(),
                socketIO(),
                highAllocation(),
                threadPoolExhaustion(),
                mixedWorkload(),
                classLoading(),
                deadlock(),
                threadWaiting(),
                deepRecursion(),
                nativeMethods(),
                producerConsumer(),
                reentrantLockScenario(),
                cpuStall(),
                ioStall()
        );
    }

    /**
     * CPU-bound stall scenario - threads are RUNNABLE but making no progress
     * because they're stuck in a tight loop waiting for a condition.
     */
    public static ScenarioDefinition cpuStall() {
        return new ScenarioDefinition() {
            private volatile boolean conditionMet = false;

            @Override
            public String getName() {
                return "cpu-stall";
            }

            @Override
            public String getDescription() {
                return "Threads stuck in busy-wait loop (RUNNABLE but no progress)";
            }

            @Override
            public void run() {
                ExecutorService executor = Executors.newFixedThreadPool(4);

                // All 4 threads are busy-waiting for a condition that never becomes true
                for (int i = 0; i < 4; i++) {
                    int threadNum = i;
                    executor.submit(() -> {
                        // Busy-wait spinning on condition
                        while (!conditionMet && !Thread.interrupted()) {
                            // Spin - this is a CPU stall pattern
                            busyWaitSpin(threadNum);
                        }
                    });
                }

                try {
                    // Let it run for the full duration - condition never met
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                executor.shutdownNow();
            }

            private void busyWaitSpin(int threadNum) {
                // CPU-intensive but useless work
                double result = 0;
                for (int i = 0; i < 1000; i++) {
                    result += Math.sin(threadNum + i) * Math.cos(threadNum - i);
                }
                // Prevent optimization
                if (result == Double.MAX_VALUE) System.out.println(result);
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectHighCPU()
                        .minThreadCount(4)
                        .build();
            }
        };
    }

    /**
     * I/O stall scenario - threads blocked waiting on slow/stalled I/O operations.
     */
    public static ScenarioDefinition ioStall() {
        return new ScenarioDefinition() {
            private ServerSocket serverSocket;
            private volatile boolean running = true;

            @Override
            public String getName() {
                return "io-stall";
            }

            @Override
            public String getDescription() {
                return "Threads stalled waiting for I/O that never arrives";
            }

            @Override
            public void setup() {
                try {
                    // Create a server that never sends data
                    serverSocket = new ServerSocket(0);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void run() {
                int port = serverSocket.getLocalPort();
                ExecutorService executor = Executors.newFixedThreadPool(6);

                // Server that accepts but never responds (simulates stalled server)
                executor.submit(() -> {
                    List<Socket> clients = new ArrayList<>();
                    while (running && !Thread.interrupted()) {
                        try {
                            Socket client = serverSocket.accept();
                            clients.add(client); // Accept but never respond
                        } catch (IOException e) {
                            // Server socket closed
                        }
                    }
                    // Cleanup
                    for (Socket client : clients) {
                        try { client.close(); } catch (IOException ignored) {}
                    }
                });

                // Client threads that connect and wait for data that never comes
                for (int i = 0; i < 5; i++) {
                    int clientNum = i;
                    executor.submit(() -> {
                        try (Socket socket = new Socket("localhost", port);
                             BufferedReader br = new BufferedReader(
                                     new InputStreamReader(socket.getInputStream()))) {
                            // Set a long timeout
                            socket.setSoTimeout(60000);

                            // Block forever waiting for a response
                            String response = br.readLine();
                            // Never reached - server never sends
                        } catch (IOException e) {
                            // Expected on shutdown
                        }
                    });
                }

                try {
                    Thread.sleep(9000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                running = false;
                executor.shutdownNow();
            }

            @Override
            public void cleanup() {
                running = false;
                try {
                    if (serverSocket != null) {
                        serverSocket.close();
                    }
                } catch (IOException e) {
                    // Ignore
                }
            }

            @Override
            public ExpectedResults getExpectedResults() {
                return ExpectedResults.builder()
                        .expectIOBlocking()
                        .minThreadCount(5)
                        .build();
            }
        };
    }
}