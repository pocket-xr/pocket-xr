package com.hayeco.flipspaces.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class VitureInterfaceChoiceTest {
    @Test fun silentInterfaceMovesToTheNext() {
        assertEquals(1, VitureInterfaceChoice.next(0, 2, 0))
    }

    @Test fun wrapsBackToTheFirst() {
        assertEquals(0, VitureInterfaceChoice.next(1, 2, 0))
    }

    @Test fun interfaceThatSentSomethingIsKept() {
        assertEquals(1, VitureInterfaceChoice.next(1, 2, 640))
    }

    @Test fun oneInterfaceStaysOnIt() {
        assertEquals(0, VitureInterfaceChoice.next(0, 1, 0))
    }

    @Test fun indexIsKeptInsideAShorterList() {
        assertEquals(0, VitureInterfaceChoice.valid(2, 2))
        assertEquals(0, VitureInterfaceChoice.valid(3, 0))
        assertEquals(1, VitureInterfaceChoice.valid(1, 2))
    }
}
