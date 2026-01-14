package org.javacs.kt.index

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.Test
import java.nio.file.Path

class JarScannerTest {

    @Test
    fun `buildPackageToJarsMap with single JAR per package`() {
        val jar1Packages = setOf("com.example.a", "com.example.b")
        val jar2Packages = setOf("org.other.c", "org.other.d")

        val map = mutableMapOf<String, MutableSet<Path>>()
        val jar1 = Path.of("/path/to/jar1.jar")
        val jar2 = Path.of("/path/to/jar2.jar")

        jar1Packages.forEach { pkg -> map.getOrPut(pkg) { mutableSetOf() }.add(jar1) }
        jar2Packages.forEach { pkg -> map.getOrPut(pkg) { mutableSetOf() }.add(jar2) }

        assertThat(map["com.example.a"], contains(jar1))
        assertThat(map["org.other.c"], contains(jar2))
        assertThat(map.keys.size, equalTo(4))
    }

    @Test
    fun `buildPackageToJarsMap with overlapping packages`() {
        val jar1Packages = setOf("kotlin.collections", "kotlin.sequences")
        val jar2Packages = setOf("kotlin.collections", "kotlinx.coroutines")

        val map = mutableMapOf<String, MutableSet<Path>>()
        val jar1 = Path.of("/path/to/stdlib.jar")
        val jar2 = Path.of("/path/to/coroutines.jar")

        jar1Packages.forEach { pkg -> map.getOrPut(pkg) { mutableSetOf() }.add(jar1) }
        jar2Packages.forEach { pkg -> map.getOrPut(pkg) { mutableSetOf() }.add(jar2) }

        assertThat(map["kotlin.collections"]?.size, equalTo(2))
        assertThat(map["kotlin.collections"], containsInAnyOrder(jar1, jar2))
        assertThat(map["kotlin.sequences"], contains(jar1))
        assertThat(map["kotlinx.coroutines"], contains(jar2))
    }

    @Test
    fun `package extraction from class path`() {
        val classPath = "com/example/MyClass.class"
        val className = classPath.removeSuffix(".class").replace("/", ".")
        val lastDot = className.lastIndexOf('.')
        val packageName = if (lastDot > 0) className.substring(0, lastDot) else ""

        assertThat(packageName, equalTo("com.example"))
    }

    @Test
    fun `package extraction from root class`() {
        val classPath = "RootClass.class"
        val className = classPath.removeSuffix(".class").replace("/", ".")
        val lastDot = className.lastIndexOf('.')
        val packageName = if (lastDot > 0) className.substring(0, lastDot) else ""

        assertThat(packageName, equalTo(""))
    }

    @Test
    fun `class file path generation from FQN`() {
        val fqClassName = "com.example.MyClass"
        val classFilePath = fqClassName.replace(".", "/") + ".class"

        assertThat(classFilePath, equalTo("com/example/MyClass.class"))
    }

    @Test
    fun `relevant packages filtering`() {
        val packageToJarsMap = mapOf(
            "com.example.a" to setOf(Path.of("/jar1.jar")),
            "com.example.b" to setOf(Path.of("/jar1.jar"), Path.of("/jar2.jar")),
            "org.other.c" to setOf(Path.of("/jar2.jar"))
        )
        val indexingJars = setOf(Path.of("/jar1.jar"))

        val relevantPackages = packageToJarsMap
            .filter { (_, jars) -> jars.any { it in indexingJars } }
            .keys

        assertThat(relevantPackages, containsInAnyOrder("com.example.a", "com.example.b"))
        assertThat(relevantPackages, not(hasItem("org.other.c")))
    }
}
