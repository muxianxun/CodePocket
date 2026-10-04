package com.dsh.codepocket

import com.dsh.codepocket.runtime.TermuxRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dependency resolver is pure logic, so it is checked here with a synthetic index
 * instead of by downloading hundreds of megabytes onto a device.
 */
class TermuxRepoTest {

    private val sample = """
Package: clang
Version: 18.0.0-1
Filename: pool/main/c/clang/clang_18.0.0-1_aarch64.deb
Size: 104857600
Depends: libc++ (>= 27), libllvm, binutils | binutils-gold, zlib

Package: libllvm
Version: 18.0.0-1
Filename: pool/main/l/libllvm/libllvm_18.0.0-1_aarch64.deb
Size: 52428800
Depends: libxml2, zlib

Package: libc++
Version: 27
Filename: pool/main/l/libc++/libc++_27_aarch64.deb
Size: 3145728

Package: binutils
Version: 2.42
Filename: pool/main/b/binutils/binutils_2.42_aarch64.deb
Size: 5242880
Depends: zlib

Package: zlib
Version: 1.3
Filename: pool/main/z/zlib/zlib_1.3_aarch64.deb
Size: 102400

Package: libxml2
Version: 2.12
Filename: pool/main/l/libxml2/libxml2_2.12_aarch64.deb
Size: 1048576
""".trimIndent()

    @Test
    fun parsesPackageFields() {
        val index = TermuxRepo.parseIndex(sample)
        assertEquals(6, index.size)
        val clang = index["clang"]!!
        assertEquals("18.0.0-1", clang.version)
        assertEquals("pool/main/c/clang/clang_18.0.0-1_aarch64.deb", clang.filename)
        assertEquals(104857600L, clang.sizeBytes)
        assertEquals(4, clang.depends.size)
    }

    @Test
    fun stripsVersionConstraintsAndKeepsAlternatives() {
        val groups = TermuxRepo.parseDepends("libc++ (>= 27), binutils | binutils-gold, zlib")
        assertEquals(listOf(listOf("libc++"), listOf("binutils", "binutils-gold"), listOf("zlib")), groups)
    }

    @Test
    fun ignoresVirtualDependencies() {
        // ${...} entries are substitutes, not package names.
        val groups = TermuxRepo.parseDepends("zlib, ${'$'}{shlibs:Depends}")
        assertEquals(listOf(listOf("zlib")), groups)
    }

    @Test
    fun resolvesTransitiveDependenciesWithoutDuplicates() {
        val index = TermuxRepo.parseIndex(sample)
        val plan = TermuxRepo.resolve("clang", index)
        val names = plan.packages.map { it.name }.sorted()
        // clang -> libc++, libllvm, binutils, zlib; libllvm -> libxml2, zlib (already seen)
        assertEquals(listOf("binutils", "clang", "libc++", "libllvm", "libxml2", "zlib"), names)
        assertEquals(0L, plan.totalBytes - (104857600L + 52428800 + 3145728 + 5242880 + 102400 + 1048576))
        assertTrue("不应有未解析依赖", plan.missing.isEmpty())
    }

    @Test
    fun picksTheAvailableAlternative() {
        // binutils exists, binutils-gold does not: the group must be satisfied, not reported missing.
        val index = TermuxRepo.parseIndex(sample)
        val plan = TermuxRepo.resolve("clang", index)
        assertTrue("binutils 应被选中", plan.packages.any { it.name == "binutils" })
        assertTrue("不应把未选中的替代品报成缺失", plan.missing.none { it == "binutils-gold" })
    }

    @Test
    fun reportsGenuinelyMissingDependencies() {
        val index = TermuxRepo.parseIndex(sample)
        // libllvm depends on libxml2 which we remove from the index.
        val trimmed = index - "libxml2"
        val plan = TermuxRepo.resolve("clang", trimmed)
        assertEquals(listOf("libxml2"), plan.missing)
    }

    @Test
    fun reportsUnknownRootWithoutCrashing() {
        val plan = TermuxRepo.resolve("nonexistent", TermuxRepo.parseIndex(sample))
        assertTrue(plan.packages.isEmpty())
        assertEquals(listOf("nonexistent"), plan.missing)
    }
}
