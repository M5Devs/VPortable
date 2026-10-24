package com.m5dev.vportable.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryExtractorTest {

    @Test
    fun testSelectAbiFolder_exactMatch() {
        val supportedAbis = arrayOf("x86_64", "arm64-v8a")
        val availableAssets = listOf("arm64-v8a", "x86_64")
        val selected = BinaryExtractor.selectAbiFolder(supportedAbis, availableAssets)
        assertEquals("x86_64", selected)
    }

    @Test
    fun testSelectAbiFolder_arm64Match() {
        val supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a")
        val availableAssets = listOf("arm64-v8a", "x86_64")
        val selected = BinaryExtractor.selectAbiFolder(supportedAbis, availableAssets)
        assertEquals("arm64-v8a", selected)
    }

    @Test
    fun testSelectAbiFolder_fallbackToFirstAvailable() {
        val supportedAbis = arrayOf("mips", "mips64")
        val availableAssets = listOf("x86_64", "arm64-v8a")
        val selected = BinaryExtractor.selectAbiFolder(supportedAbis, availableAssets)
        assertEquals("x86_64", selected)
    }
}
