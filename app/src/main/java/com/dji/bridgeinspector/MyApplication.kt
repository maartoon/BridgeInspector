package com.dji.bridgeinspector

import android.app.Application
import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// 1. Add this sealed class to represent connection events
sealed class ConnectionEvent {
    object ProductConnected : ConnectionEvent()
    object ProductDisconnected : ConnectionEvent()
}

class MyApplication : Application() {

    private val TAG = this::class.simpleName

    // 2. Add a SharedFlow to broadcast events
    private val _connectionState = MutableSharedFlow<ConnectionEvent>(replay = 1)
    val connectionState = _connectionState.asSharedFlow()


    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // MSDK v5.10.0 and later
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        SDKManager.getInstance().init(this, object:SDKManagerCallback{
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                Log.i(TAG, "onInitProcess: ")
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    SDKManager.getInstance().registerApp()
                }
            }
            override fun onRegisterSuccess() {
                Log.i(TAG, "Mavic onRegisterSuccess: ")
            }
            override fun onRegisterFailure(error: IDJIError?) {
                Log.i(TAG, "Mavic onRegisterFailure: $error")
            }
            override fun onProductConnect(productId: Int) {
                Log.i(TAG, "Mavic onProductConnect: ")
                // 3. Emit a connected event
                _connectionState.tryEmit(ConnectionEvent.ProductConnected)
            }
            override fun onProductDisconnect(productId: Int) {
                Log.i(TAG, "Mavic onProductDisconnect: ")
                // 4. Emit a disconnected event
                _connectionState.tryEmit(ConnectionEvent.ProductDisconnected)
            }
            override fun onProductChanged(productId: Int)
            {
                Log.i(TAG, "Mavic onProductChanged: ")
            }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                Log.i(TAG, "Mavic onDatabaseDownloadProgress: ${current/total}")
            }
        })
    }
}