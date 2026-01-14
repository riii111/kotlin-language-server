package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.progress.Progress
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SymbolIndexProgressTest {

    @Test
    fun `RecordingProgress captures update calls`() {
        val progress = RecordingProgress()

        progress.update(message = "test", percent = 50)
        progress.update(message = "done", percent = 100)
        progress.close()

        assertThat(progress.updates.size, equalTo(2))
        assertThat(progress.updates[0], equalTo(ProgressUpdate("test", 50)))
        assertThat(progress.updates[1], equalTo(ProgressUpdate("done", 100)))
        assertThat(progress.closed, equalTo(true))
    }

    @Test
    fun `RecordingProgressFactory creates RecordingProgress`() {
        val factory = RecordingProgress.Factory()
        val progress = factory.create("Indexing").get()

        assertThat(progress, instanceOf(RecordingProgress::class.java))
        assertThat(factory.label, equalTo("Indexing"))
    }

    @Test
    fun `progress percentage calculation for empty list`() {
        val packages = emptyList<String>()
        val percent = if (packages.isEmpty()) 100 else ((0 + 1) * 100) / packages.size

        assertThat(percent, equalTo(100))
    }

    @Test
    fun `progress percentage calculation for single item`() {
        val packages = listOf("kotlin")
        val percent = ((0 + 1) * 100) / packages.size

        assertThat(percent, equalTo(100))
    }

    @Test
    fun `progress percentage calculation for multiple items`() {
        val packages = listOf("kotlin", "kotlin.collections", "kotlin.io", "kotlin.text")

        val percentages = packages.indices.map { index ->
            ((index + 1) * 100) / packages.size
        }

        assertThat(percentages, equalTo(listOf(25, 50, 75, 100)))
    }
}

data class ProgressUpdate(val message: String?, val percent: Int?)

class RecordingProgress : Progress {
    val updates = mutableListOf<ProgressUpdate>()
    var closed = false

    override fun update(message: String?, percent: Int?) {
        updates.add(ProgressUpdate(message, percent))
    }

    override fun close() {
        closed = true
    }

    class Factory : Progress.Factory {
        var label: String? = null
        private val progress = RecordingProgress()

        override fun create(label: String): CompletableFuture<Progress> {
            this.label = label
            return CompletableFuture.completedFuture(progress)
        }

        fun getProgress(): RecordingProgress = progress
    }
}
