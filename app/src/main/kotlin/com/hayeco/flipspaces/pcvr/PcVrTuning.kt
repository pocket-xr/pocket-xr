package com.hayeco.flipspaces.pcvr

/** Live settings for PC VR (the debug "pcvr" command sets them). */
object PcVrTuning {
    /** View tangents scaled by this: 1 is the glasses' true view, 1.3 shows 30 % more around you. */
    @Volatile var zoom = 1.3f

    /** The hardware decoder's operating rate hint, frames a second; 0 leaves it out. Read when a stream starts. */
    @Volatile var operatingRate = 120

    /**
     * Tracked hands' aim: SteamVR's laser on the emulated Quest controller is turned this much from the pose sent, so
     * the pose is turned back by it. Degrees; pitch up, yaw toward the body's middle (mirrored for the left hand).
     */
    @Volatile var aimPitchDeg = 0f
    @Volatile var aimYawDeg = 0f

    /** ALVR moves every controller this far forward along its aim (left_controller_position_offset); taken back out. */
    @Volatile var controllerForwardM = 0.11f

    /** Test: draw straight from the decoder's image, without the copy. */
    @Volatile var direct = false
}
