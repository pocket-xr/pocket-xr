package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.1.0 (a One Pro on an Honor Magic V5: the picture upside down): the glasses' USB product decides the model when the
 * phone's name for their display does not, so a One family model never gets the Aura's axes ([GlassesModel.detect]).
 */
class GlassesModel0110Test {
    @Test fun aDisplayThePhoneNamedItselfFallsBackToTheUsbProduct() {
        assertEquals(GlassesModel.ONE, GlassesModel.detect(GlassesId("HDMI", ""), GlassesModel.USB_ONE))
        assertEquals(GlassesModel.AURA, GlassesModel.detect(GlassesId("External display", ""), GlassesModel.USB_AURA))
    }

    @Test fun anXrealProductNotKnownYetIsXrealWithTheOneFamilysAxes() {
        val m = GlassesModel.detect(GlassesId("HDMI", ""), 0x0437)
        assertEquals(GlassesModel.OTHER_XREAL, m)
        assertEquals(AxisMap.XRIGHT_YDOWN_ZFRONT, m.defaultAxes)
        assertEquals(GlassesModel.OTHER_XREAL, GlassesModel.detect(null, 0x0437))
    }

    @Test fun onlyTheAuraKeepsTheDefaultAxes() {
        GlassesModel.entries.filter { it != GlassesModel.AURA && it != GlassesModel.AIR && it != GlassesModel.VITURE && it != GlassesModel.UNKNOWN }.forEach {
            assertEquals(it.name, AxisMap.XRIGHT_YDOWN_ZFRONT, it.defaultAxes)
        }
        assertEquals(AxisMap.DEFAULT, GlassesModel.AURA.defaultAxes)
    }

    /** 1.1.12: the Air family's reports are in head axes when read ([AirImuProtocol.parse]), so it keeps the default. */
    @Test fun theAirFamilyIsFoundByItsUsbProductAndKeepsTheDefaultAxes() {
        for (pid in listOf(AirImuProtocol.PID_AIR, AirImuProtocol.PID_AIR_2, AirImuProtocol.PID_AIR_2_PRO, AirImuProtocol.PID_AIR_2_ULTRA)) {
            assertEquals(GlassesModel.AIR, GlassesModel.detect(GlassesId("HDMI", ""), pid))
            assertEquals(GlassesModel.AIR, GlassesModel.detect(null, pid))
        }
        assertEquals(AxisMap.DEFAULT, GlassesModel.AIR.defaultAxes)
        assertEquals(GlassesModel.OTHER_XREAL, GlassesModel.detect(null, 0x0442))
    }

    @Test fun aNameThatSaysTheModelStillWins() {
        assertEquals(GlassesModel.ONE_PRO, GlassesModel.detect(GlassesId("XREAL One Pro", ""), 0x0437))
        assertEquals(GlassesModel.AURA, GlassesModel.detect(GlassesId("XREAL 1 Ultra", "16656"), GlassesModel.USB_ONE))
    }

    @Test fun aMonitorWithNoXrealUsbStaysUnknown() {
        assertEquals(GlassesModel.UNKNOWN, GlassesModel.detect(GlassesId("LG Monitor", "1234"), null))
    }

    /** 1.1.4 (issue 26): the factory display mount only where it was checked, the Aura. */
    @Test fun theFactoryMountIsUsedOnlyOnTheAura() {
        assertEquals(listOf(GlassesModel.AURA), GlassesModel.entries.filter { it.factoryMountChecked })
    }
}
