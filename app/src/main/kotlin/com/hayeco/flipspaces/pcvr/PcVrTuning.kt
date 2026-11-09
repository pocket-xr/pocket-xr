package com.hayeco.flipspaces.pcvr

/** Live settings for PC VR (the debug "pcvr" command sets them). */
object PcVrTuning {
    /** View tangents scaled by this: 1 is the glasses' true view, 1.3 shows 30 % more around you. */
    @Volatile var zoom = 1.3f
}
