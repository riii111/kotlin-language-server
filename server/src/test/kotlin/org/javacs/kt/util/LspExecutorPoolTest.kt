package org.javacs.kt.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LspExecutorPoolTest {

    private var pool: LspExecutorPool? = null

    @After
    fun cleanup() {
        pool?.close()
    }

    @Test
    fun `compute executes task and returns result`() {
        pool = LspExecutorPool()

        val result = pool!!.compute { 42 }.get(5, TimeUnit.SECONDS)

        assertThat(result, equalTo(42))
    }

    @Test
    fun `submit with DEFINITION executes on dedicated executor`() {
        pool = LspExecutorPool()
        val threadName = pool!!.submit(LspOperation.DEFINITION) {
            Thread.currentThread().name
        }.get(5, TimeUnit.SECONDS)

        assertThat(threadName, containsString("kls-definition"))
    }

    @Test
    fun `submit with HOVER executes on dedicated executor`() {
        pool = LspExecutorPool()
        val threadName = pool!!.submit(LspOperation.HOVER) {
            Thread.currentThread().name
        }.get(5, TimeUnit.SECONDS)

        assertThat(threadName, containsString("kls-hover"))
    }

    @Test
    fun `submit with COMPLETION executes on dedicated executor`() {
        pool = LspExecutorPool()
        val threadName = pool!!.submit(LspOperation.COMPLETION) {
            Thread.currentThread().name
        }.get(5, TimeUnit.SECONDS)

        assertThat(threadName, containsString("kls-completion"))
    }

    @Test
    fun `submit with REFERENCES executes on dedicated executor`() {
        pool = LspExecutorPool()
        val threadName = pool!!.submit(LspOperation.REFERENCES) {
            Thread.currentThread().name
        }.get(5, TimeUnit.SECONDS)

        assertThat(threadName, containsString("kls-references"))
    }

    @Test
    fun `different operations run on different executors`() {
        pool = LspExecutorPool()
        val latch = CountDownLatch(4)
        val threadNames = mutableSetOf<String>()
        val lock = Object()

        listOf(
            LspOperation.DEFINITION,
            LspOperation.HOVER,
            LspOperation.COMPLETION,
            LspOperation.REFERENCES
        ).forEach { op ->
            pool!!.submit(op) {
                synchronized(lock) {
                    threadNames.add(Thread.currentThread().name)
                }
                latch.countDown()
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertThat("All tasks should complete", completed, equalTo(true))
        assertThat("Each operation should use its own thread",
            threadNames.size, equalTo(4))
    }

    @Test
    fun `concurrent tasks on same operation queue serially`() {
        pool = LspExecutorPool()
        val executionOrder = mutableListOf<Int>()
        val lock = Object()
        val tasksCount = 5

        val futures = (1..tasksCount).map { i ->
            pool!!.submit(LspOperation.DEFINITION) {
                Thread.sleep(10) // Small delay to ensure ordering
                synchronized(lock) {
                    executionOrder.add(i)
                }
                i
            }
        }

        futures.forEach { it.get(5, TimeUnit.SECONDS) }

        assertThat("All tasks should complete", executionOrder.size, equalTo(tasksCount))
        assertThat("Tasks should execute in order",
            executionOrder, equalTo(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `close terminates all executors`() {
        pool = LspExecutorPool()
        val counter = AtomicInteger(0)

        // Submit a few tasks
        repeat(3) {
            pool!!.compute { counter.incrementAndGet() }
        }

        // Wait for tasks to complete
        Thread.sleep(100)

        // Close should complete without blocking indefinitely
        pool!!.close()
        pool = null // Mark as closed so @After doesn't try to close again

        assertThat(counter.get(), equalTo(3))
    }
}
