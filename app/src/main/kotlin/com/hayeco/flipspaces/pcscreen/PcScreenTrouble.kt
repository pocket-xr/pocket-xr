package com.hayeco.flipspaces.pcscreen

import com.limelight.nvstream.http.HostHttpResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * PC screens: what went wrong, in the user's terms, so the setup page can say what to do instead of an error code.
 */
enum class PcScreenTrouble {
    /** The PC did not answer: off, asleep, Apollo not running, another network, or a firewall. */
    UNREACHABLE,

    /** Apollo knows the phone but has not given it the right to launch, or to use the mouse and keyboard (403). */
    NO_PERMISSION,

    /** The host does not know this phone (any more): pair again (401, or a new host certificate). */
    NOT_PAIRED,

    OTHER;

    companion object {
        fun of(e: Throwable?): PcScreenTrouble {
            var t = e
            while (t != null) {
                when (t) {
                    is HostHttpResponseException -> return ofCode(t.errorCode)
                    is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException -> return UNREACHABLE
                }
                t = t.cause
            }
            return OTHER
        }

        /** An HTTP status from the host, also what a failed launch stage reports. */
        fun ofCode(code: Int): PcScreenTrouble = when (code) {
            403 -> NO_PERMISSION
            401 -> NOT_PAIRED
            else -> OTHER
        }
    }
}
