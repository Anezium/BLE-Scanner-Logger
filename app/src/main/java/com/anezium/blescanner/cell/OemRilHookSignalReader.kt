package com.anezium.blescanner.cell

import android.os.IBinder
import android.os.Parcel
import android.telephony.SignalStrength
import android.util.Log

class OemRilHookSignalReader {
    private var binder: IBinder? = null

    fun readSignalStrength(phoneId: Int = 0): SignalStrength? {
        val remote = binder.takeIf { it?.isBinderAlive == true } ?: getBinder()?.also { binder = it }
        if (remote == null) return null

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(phoneId)
            if (!remote.transact(TRANSACTION_GET_SIGNAL_STRENGTH, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() == 0) null else SignalStrength.CREATOR.createFromParcel(reply)
        } catch (error: Throwable) {
            Log.w(TAG, "OEM RIL hook getSignalStrength failed", error)
            binder = null
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun getBinder(): IBinder? =
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            getService.invoke(null, SERVICE_NAME) as? IBinder
        } catch (error: Throwable) {
            Log.w(TAG, "OEM RIL hook service lookup failed", error)
            null
        }

    companion object {
        private const val TAG = "OEM_RILHOOK_SIGNAL"
        private const val SERVICE_NAME = "telephony.oem.oemrilhook"
        private const val DESCRIPTOR = "com.samsung.slsi.telephony.oem.oemrilhook.IOemRilHook"
        private const val TRANSACTION_GET_SIGNAL_STRENGTH = 8
    }
}
