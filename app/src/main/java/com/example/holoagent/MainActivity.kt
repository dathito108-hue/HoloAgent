package com.example.holoagent

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.holoagent.AgentGoal
import com.holoagent.HoloMasterOmniAgiCollectiveV18
import com.holoagent.Point3D

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = "HoloAgent Adaptive Runtime đang khởi chạy..."
            textSize = 17f
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)

        Thread {
            val node = HoloMasterOmniAgiCollectiveV18(
                nodeId = "agent_sentinel_01",
                ipAddress = "local",
                context = this@MainActivity
            )

            val goal = AgentGoal(
                id = "adaptive_mobile_runtime",
                description = "Quan sát, ghi nhớ, suy luận, lập kế hoạch và cải thiện qua phản hồi",
                priority = 0.9f
            )

            val report = StringBuilder()
            repeat(3) { cycleIndex ->
                val tokenValue = 0.08f + cycleIndex * 0.02f
                val tokens = Array(4 + cycleIndex) {
                    FloatArray(2048) { i -> tokenValue + (i % 17) * 0.0001f }
                }
                val lidar = List(100) { i ->
                    Point3D(
                        x = (i % 10) * 0.1f,
                        y = 0.75f + cycleIndex * 0.01f,
                        z = (i / 10) * 0.1f
                    )
                }

                val result = node.executeIntelligenceCycle(tokens, lidar, goal)

                report.append("Chu kỳ ").append(result.stateAfter.cycle).append('\n')
                report.append("Reward: ").append("%.3f".format(result.reward)).append('\n')
                report.append("Confidence: ").append("%.3f".format(result.stateAfter.confidence)).append('\n')
                report.append("Novelty: ").append("%.3f".format(result.stateAfter.novelty)).append('\n')
                report.append("Kế hoạch: ")
                    .append(result.plan.joinToString { it.type.name })
                    .append('\n')
                report.append("Thành công: ")
                    .append(result.results.count { it.success })
                    .append("/")
                    .append(result.results.size)
                    .append("\n\n")
            }

            report.append("Kinh nghiệm bền vững: ").append(node.experienceCount()).append("\n")
            report.append("Đóng Activity rồi mở lại vẫn khôi phục State/Experience/Reasoning.")

            runOnUiThread {
                status.text = "HoloAgent Adaptive Runtime\n\n" + report
            }
        }.start()
    }
}
