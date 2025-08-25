package com.dji.bridgeinspector


import android.graphics.PointF
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.ux.core.widget.fpv.FPVWidget
import com.dji.bridgeinspector.WaypointOverlayWidget
import dji.v5.manager.SDKManager


open class MainActivity : AppCompatActivity() {
    private var primaryFpvWidget: FPVWidget? = null
//    private var secondaryFpvWidget: FPVWidget? = null
    private var waypointWidget: WaypointOverlayWidget? = null
    private var pos: PointF? = PointF(950f, 500f)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.live_feed2)

        primaryFpvWidget = findViewById(R.id.widget_primary_fpv)
        waypointWidget = findViewById(R.id.waypoint)
//        secondaryFpvWidget = findViewById(R.id.widget_secondary_fpv)

        primaryFpvWidget?.updateVideoSource(ComponentIndexType.LEFT_OR_MAIN)
        waypointWidget?.update(pos)
//        secondaryFpvWidget?.updateVideoSource(ComponentIndexType.FPV)

    }


}