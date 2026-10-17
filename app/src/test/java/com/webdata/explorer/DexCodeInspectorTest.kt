package com.webdata.explorer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexCodeInspectorTest {
    @Test
    fun malformedDexReturnsReadableWarningInsteadOfCrashing() {
        val result = DexCodeInspector.inspect(byteArrayOf(1, 2, 3, 4, 5))
        assertNotNull(result)
        assertTrue(result.warning?.contains("No se pudo interpretar") == true)
    }

    @Test
    fun emptyDexReturnsReadableWarningInsteadOfCrashing() {
        val result = DexCodeInspector.inspect(byteArrayOf())
        assertNotNull(result)
        assertTrue(result.warning?.contains("No se pudo interpretar") == true)
    }
}
