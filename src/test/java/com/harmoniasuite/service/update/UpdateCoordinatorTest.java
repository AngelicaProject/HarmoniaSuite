package com.harmoniasuite.service.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateCoordinatorTest {

    @Test
    @DisplayName("Serializes update operations")
    void serializesUpdateOperations() throws Exception {
        UpdateCoordinator coordinator = new UpdateCoordinator();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> coordinator.run(() -> {
                firstEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> coordinator.run(secondEntered::countDown));
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get();
            second.get();
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
}
