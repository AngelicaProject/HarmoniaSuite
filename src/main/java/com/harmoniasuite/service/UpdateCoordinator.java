package com.harmoniasuite.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class UpdateCoordinator {

    private final ReentrantLock lock = new ReentrantLock();

    <T> T read(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    void run(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
