package com.holoagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

data class PersistedExperience(
    val cycle: Long,
    val observation: AgentObservation,
    val reward: Float,
    val successfulActions: Set<ActionType>
)

data class PersistedCausalRelation(
    val cause: String,
    val effect: String,
    val strength: Float,
    val observations: Int
)

class PersistentCognitiveMemory(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "holo_cognitive_memory_v1",
            Context.MODE_PRIVATE
        )

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    fun save(
        state: AgentState,
        experiences: List<PersistedExperience>,
        relations: List<PersistedCausalRelation>
    ) {
        val stateValue = listOf(
            state.cycle.toString(),
            state.confidence.toString(),
            state.novelty.toString(),
            state.stability.toString(),
            state.lastReward.toString(),
            state.activeGoal?.id.orEmpty(),
            state.activeGoal?.description.orEmpty(),
            (state.activeGoal?.priority ?: 0f).toString()
        ).joinToString("|", transform = ::encode)

        val experienceValue = experiences.joinToString("\n") { item ->
            listOf(
                item.cycle.toString(),
                item.observation.tokenCount.toString(),
                item.observation.memorySimilarity.toString(),
                item.observation.spatialSurfaceCount.toString(),
                item.observation.inferenceLatencyNs.toString(),
                item.observation.economicOpportunity.toString(),
                item.observation.cycleSucceeded.toString(),
                item.reward.toString(),
                item.successfulActions.joinToString(",") { it.name }
            ).joinToString("|", transform = ::encode)
        }

        val relationValue = relations.joinToString("\n") { relation ->
            listOf(
                relation.cause,
                relation.effect,
                relation.strength.toString(),
                relation.observations.toString()
            ).joinToString("|", transform = ::encode)
        }

        preferences.edit()
            .putString("state", stateValue)
            .putString("experiences", experienceValue)
            .putString("relations", relationValue)
            .apply()
    }

    fun loadState(): AgentState {
        val raw = preferences.getString("state", null) ?: return AgentState()
        return runCatching {
            val values = raw.split("|").map(::decode)
            require(values.size >= 8)
            AgentState(
                cycle = values[0].toLong(),
                confidence = values[1].toFloat(),
                novelty = values[2].toFloat(),
                stability = values[3].toFloat(),
                lastReward = values[4].toFloat(),
                activeGoal = if (values[5].isBlank()) {
                    null
                } else {
                    AgentGoal(values[5], values[6], values[7].toFloat())
                }
            )
        }.getOrDefault(AgentState())
    }

    fun loadExperiences(): List<PersistedExperience> {
        return preferences.getString("experiences", "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                runCatching {
                    val values = line.split("|").map(::decode)
                    require(values.size >= 9)
                    PersistedExperience(
                        cycle = values[0].toLong(),
                        observation = AgentObservation(
                            tokenCount = values[1].toInt(),
                            memorySimilarity = values[2].toFloat(),
                            spatialSurfaceCount = values[3].toInt(),
                            inferenceLatencyNs = values[4].toLong(),
                            economicOpportunity = values[5].toBoolean(),
                            cycleSucceeded = values[6].toBoolean()
                        ),
                        reward = values[7].toFloat(),
                        successfulActions = values[8]
                            .split(",")
                            .filter(String::isNotBlank)
                            .mapNotNull { name ->
                                runCatching { ActionType.valueOf(name) }.getOrNull()
                            }
                            .toSet()
                    )
                }.getOrNull()
            }
            .toList()
    }

    fun loadRelations(): List<PersistedCausalRelation> {
        return preferences.getString("relations", "")
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                runCatching {
                    val values = line.split("|").map(::decode)
                    require(values.size >= 4)
                    PersistedCausalRelation(
                        cause = values[0],
                        effect = values[1],
                        strength = values[2].toFloat(),
                        observations = values[3].toInt()
                    )
                }.getOrNull()
            }
            .toList()
    }
}
