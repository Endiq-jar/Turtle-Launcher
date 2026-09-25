package kr.co.donghyun.flamelauncher.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftSupportTest {
    @Test
    fun onlyTheRequestedReleaseIsSupported() {
        assertTrue(MinecraftSupport.isSupported("26.3"))
        assertFalse(MinecraftSupport.isSupported("1.21.1"))
        assertFalse(MinecraftSupport.isSupported("26.2"))
        assertFalse(MinecraftSupport.isSupported("26.3-pre1"))
    }
}
