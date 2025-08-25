package com.dji.bridgeinspector

import android.app.Application
import android.content.Context
import android.util.Log
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

class MyApplication : Application() {

    private val TAG = this::class.simpleName

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // 在调用install前，请勿调用任何MSDK相关
        // MSDK v5.10.0 之前的版本请使用 com.secneo.sdk.Helper.install(this)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化MSDK，建议初始化逻辑放在Application中，当然也可以根据自己的需要放在任意地方。
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
            }
            override fun onProductDisconnect(productId: Int) {
                Log.i(TAG, "Mavic onProductDisconnect: ")
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