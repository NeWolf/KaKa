package com.newolf.kaka.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 覆盖"模拟收到消息"功能所依赖的三态映射（display / stored / isOnWork），
 * 保证 UI 传给 Service 的字符串能被 Service 解析回正确的 isOnWork。
 */
class PunchTypeMapperTest {

    @Test
    fun displayToStored_shangban_mapsToShangban() {
        assertEquals("上班", PunchTypeMapper.displayToStored("上班"))
    }

    @Test
    fun displayToStored_xiaban_mapsToXiaban() {
        assertEquals("下班", PunchTypeMapper.displayToStored("下班"))
    }

    @Test
    fun displayToStored_auto_mapsToAuto() {
        assertEquals("auto", PunchTypeMapper.displayToStored("自动"))
    }

    @Test
    fun displayToStored_unknownFallsBackToAuto() {
        assertEquals("auto", PunchTypeMapper.displayToStored(""))
        assertEquals("auto", PunchTypeMapper.displayToStored("随机值"))
    }

    @Test
    fun storedToDisplay_roundTripsAllValues() {
        PunchTypeMapper.displayOptions.forEach { display ->
            val stored = PunchTypeMapper.displayToStored(display)
            assertEquals(display, PunchTypeMapper.storedToDisplay(stored))
        }
    }

    @Test
    fun storedToDisplay_nullFallsBackToAuto() {
        assertEquals("自动", PunchTypeMapper.storedToDisplay(null))
    }

    @Test
    fun storedToIsOnWork_shangbanIsTrue() {
        assertEquals(true, PunchTypeMapper.storedToIsOnWork("上班"))
    }

    @Test
    fun storedToIsOnWork_xiabanIsFalse() {
        assertEquals(false, PunchTypeMapper.storedToIsOnWork("下班"))
    }

    @Test
    fun storedToIsOnWork_autoIsNull() {
        assertNull(PunchTypeMapper.storedToIsOnWork("auto"))
    }

    @Test
    fun storedToIsOnWork_nullIsNull() {
        assertNull(PunchTypeMapper.storedToIsOnWork(null))
    }

    @Test
    fun displayOptions_containsExpectedThreeValues() {
        assertEquals(listOf("自动", "上班", "下班"), PunchTypeMapper.displayOptions)
    }
}