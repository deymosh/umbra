package com.umbra.app.util

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLoadPriorityGate @Inject constructor() {
    private val activeInteractiveLoads = AtomicInteger(0)
    private val interactiveLoadStartedListeners = CopyOnWriteArraySet<() -> Unit>()

    val isInteractiveLoadActive: Boolean
        get() = activeInteractiveLoads.get() > 0

    fun beginInteractiveLoad(): AutoCloseable {
        if (activeInteractiveLoads.incrementAndGet() == 1) {
            interactiveLoadStartedListeners.forEach { it() }
        }

        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                activeInteractiveLoads.decrementAndGet()
            }
        }
    }

    fun addInteractiveLoadStartedListener(listener: () -> Unit): AutoCloseable {
        interactiveLoadStartedListeners.add(listener)
        if (isInteractiveLoadActive) listener()
        return AutoCloseable { interactiveLoadStartedListeners.remove(listener) }
    }
}