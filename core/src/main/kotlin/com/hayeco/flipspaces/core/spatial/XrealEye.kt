package com.hayeco.flipspaces.core.spatial

/**
 * 1.4.1 (docs/6DOF-PLAN.md): whether an XREAL Eye camera is on the One series glasses. The One family has no camera of
 * its own, so any USB video interface on an XREAL device counts as the Eye. Read from Android's USB list only: no
 * permission, nothing opened or sent. Not yet confirmed on real glasses: the Glasses report says what was seen, for testers.
 */
object XrealEye {
    private const val XREAL_VENDOR = 0x3318
    private const val VIDEO_CLASS = 14

    /** The glasses where an Eye is what gives 6DoF. */
    fun oneFamily(model: GlassesModel?): Boolean =
        model == GlassesModel.ONE || model == GlassesModel.ONE_PRO || model == GlassesModel.ONE_PRO_PLUS || model == GlassesModel.ONE_S

    /** The XREAL USB device that carries a camera, or null. */
    fun camera(usb: List<GlassesReport.Usb>): GlassesReport.Usb? =
        usb.firstOrNull { u -> u.vendorId == XREAL_VENDOR && u.infos.any { it.cls == VIDEO_CLASS } }

    /** An Eye on One series glasses. */
    fun seen(model: GlassesModel?, usb: List<GlassesReport.Usb>): Boolean = oneFamily(model) && camera(usb) != null

    /** One line for the Glasses report on One series glasses, else null. */
    fun reportLine(model: GlassesModel?, usb: List<GlassesReport.Usb>): String? {
        if (!oneFamily(model)) return null
        val cam = camera(usb) ?: return "XREAL Eye: not seen (no camera on the glasses' USB), so Steady mode in 6DoF is not offered"
        return "XREAL Eye: seen (a camera on USB %04X:%04X \"%s\"), so Steady mode in 6DoF is offered".format(java.util.Locale.ROOT, cam.vendorId, cam.productId, cam.name)
    }
}
