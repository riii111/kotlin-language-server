package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test

class SymbolIndexBatchTest {

    @Test
    fun `batch chunking with exact division`() {
        val packages = (1..100).map { "pkg$it" }
        val batchSize = 25
        val batches = packages.chunked(batchSize)

        assertThat(batches.size, equalTo(4))
        assertThat(batches.all { it.size == 25 }, equalTo(true))
    }

    @Test
    fun `batch chunking with remainder`() {
        val packages = (1..107).map { "pkg$it" }
        val batchSize = 50
        val batches = packages.chunked(batchSize)

        assertThat(batches.size, equalTo(3))
        assertThat(batches[0].size, equalTo(50))
        assertThat(batches[1].size, equalTo(50))
        assertThat(batches[2].size, equalTo(7))
    }

    @Test
    fun `batch chunking with single batch`() {
        val packages = (1..30).map { "pkg$it" }
        val batchSize = 50
        val batches = packages.chunked(batchSize)

        assertThat(batches.size, equalTo(1))
        assertThat(batches[0].size, equalTo(30))
    }

    @Test
    fun `batch chunking with empty list`() {
        val packages = emptyList<String>()
        val batchSize = 50
        val batches = packages.chunked(batchSize)

        assertThat(batches.size, equalTo(0))
    }

    @Test
    fun `batch chunking with batch size of 1`() {
        val packages = listOf("pkg1", "pkg2", "pkg3")
        val batchSize = 1
        val batches = packages.chunked(batchSize)

        assertThat(batches.size, equalTo(3))
        assertThat(batches.all { it.size == 1 }, equalTo(true))
    }

    @Test
    fun `progress percentage calculation for batched indexing`() {
        val totalPackages = 100
        val batchSize = 25
        val batches = (1..totalPackages).chunked(batchSize)

        val percentages = mutableListOf<Int>()
        var processedPackages = 0

        for (batch in batches) {
            processedPackages += batch.size
            val percent = (processedPackages * 100) / totalPackages
            percentages.add(percent)
        }

        assertThat(percentages, equalTo(listOf(25, 50, 75, 100)))
    }

    @Test
    fun `progress percentage calculation for uneven batches`() {
        val totalPackages = 107
        val batchSize = 50
        val batches = (1..totalPackages).chunked(batchSize)

        val percentages = mutableListOf<Int>()
        var processedPackages = 0

        for (batch in batches) {
            processedPackages += batch.size
            val percent = (processedPackages * 100) / totalPackages
            percentages.add(percent)
        }

        assertThat(percentages, equalTo(listOf(46, 93, 100)))
    }

    @Test
    fun `batch info message format`() {
        val batches = listOf(listOf("a"), listOf("b"), listOf("c"))

        val messages = batches.mapIndexed { index, _ ->
            "Batch ${index + 1}/${batches.size}"
        }

        assertThat(messages, equalTo(listOf("Batch 1/3", "Batch 2/3", "Batch 3/3")))
    }
}
