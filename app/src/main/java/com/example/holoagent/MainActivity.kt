package com.example.holoagent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.holoagent.HoloMasterOmniAgiCollectiveV18
import com.holoagent.Point3D

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "HoloAgent V18.0 đang khởi chạy..."
            textSize = 18f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)

        Thread {
            val node = HoloMasterOmniAgiCollectiveV18("agent_sentinel_01", "10.0.0.12")
            val tokens = Array(4) { FloatArray(2048) { 0.1f } }
            val lidar = List(100) { i -> Point3D((i % 10) * 0.1f, 0.75f, (i / 10) * 0.1f) }
            val success = node.executeMasterCycle(tokens, lidar)
            runOnUiThread {
                status.text = if (success) {
                    "HoloAgent V18.0
Master Cycle: THÀNH CÔNG"
                } else {
                    "HoloAgent V18.0
Master Cycle: HOÀN TẤT CHU KỲ"
                }
            }
        }.start()
    }
}