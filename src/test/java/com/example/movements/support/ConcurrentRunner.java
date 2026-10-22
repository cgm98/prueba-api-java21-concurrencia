package com.example.movements.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ConcurrentRunner {

    private ConcurrentRunner() {
    }

    public record ExecutionSummary<T>(
            List<T> successes,
            List<Throwable> errors,
            long durationMs
    ) {}

    public static <T> ExecutionSummary<T> run(int concurrency, Callable<T> task) throws InterruptedException {
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<T>> futures = new ArrayList<>(concurrency);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return task.call();
                }));
            }

            // Wait until all virtual threads are created and waiting at the barrier
            readyLatch.await(30, TimeUnit.SECONDS);

            long startTime = System.currentTimeMillis();
            // Release the floodgates simultaneously!
            startLatch.countDown();

            List<T> successes = new ArrayList<>();
            List<Throwable> errors = new ArrayList<>();

            for (Future<T> future : futures) {
                try {
                    successes.add(future.get(120, TimeUnit.SECONDS));
                } catch (Exception e) {
                    errors.add(e.getCause() != null ? e.getCause() : e);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            return new ExecutionSummary<>(successes, errors, durationMs);
        }
    }
}
