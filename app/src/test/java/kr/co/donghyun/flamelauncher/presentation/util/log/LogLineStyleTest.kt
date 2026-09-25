package kr.co.donghyun.flamelauncher.presentation.util.log

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLineStyleTest {
    @Test
    fun errorsAndWarningsAreDistinct() {
        assertEquals(LogSeverity.ERROR, LogLineStyle.severity("E/AndroidRuntime: FATAL EXCEPTION"))
        assertEquals(LogSeverity.WARNING, LogLineStyle.severity("WARN: shader fallback"))
    }

    @Test
    fun ordinaryLinesRemainUnhighlighted() {
        assertEquals(LogSeverity.NORMAL, LogLineStyle.severity("[main/INFO]: Loading 42 assets"))
        assertEquals(LogSeverity.NORMAL, LogLineStyle.severity("the word warning appears as prose"))
    }

    @Test
    fun styledTextPreservesEveryLine() {
        val source = "INFO hello\nWARN slow\nERROR failed"
        assertEquals(source, LogLineStyle.styled(source).text)
    }
}
