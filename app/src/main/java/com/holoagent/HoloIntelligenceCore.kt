package com.holoagent

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ActionType {
    CONSOLIDATE_MEMORY,
    RUN_INFERENCE,
    ANALYZE_SPATIAL,
    SHARE_STATE,
    EVALUATE_ECONOMIC_TASK,
    IDLE
}

data class AgentGoal(
    val id: String,
    val description: String,
    val priority: Float = 0.5f
)

data class AgentObservation(
    val tokenCount: Int,
    val memorySimilarity: Float,
    val spatialSurfaceCount: Int,
    val inferenceLatencyNs: Long,
    val economicOpportunity: Boolean,
    val cycleSucceeded: Boolean
)

data class AgentState(
    val cycle: Long = 0,
    val confidence: Float = 0.5f,
    val novelty: Float = 1f,
    val stability: Float = 0.5f,
    val lastReward: Float = 0f,
    val activeGoal: AgentGoal? = null
)

data class PlannedAction(
    val type: ActionType,
    val reason: String,
    val expectedUtility: Float,
    val stepId: String? = null,
    val sequence: Int = 0
)

data class ActionResult(
    val action: PlannedAction,
    val success: Boolean,
    val signal: Float,
    val detail: String
)

data class IntelligenceCycleResult(
    val stateBefore: AgentState,
    val stateAfter: AgentState,
    val plan: List<PlannedAction>,
    val multiStepPlan: MultiStepPlan = MultiStepPlan("", emptyList()),
    val results: List<ActionResult>,
    val reward: Float
)

class AdaptiveExperienceMemory(private val capacity: Int = 128) {
    data class Experience(
        val cycle: Long,
        val observation: AgentObservation,
        val reward: Float,
        val successfulActions: Set<ActionType>
    )

    private val experiences = ArrayDeque<Experience>()

    @Synchronized
    fun append(experience: Experience) {
        if (experiences.size >= capacity) experiences.removeFirst()
        experiences.addLast(experience)
    }

    @Synchronized
    fun actionSuccessRate(type: ActionType): Float {
        if (experiences.isEmpty()) return 0.5f
        var seen = 0
        var success = 0
        for (experience in experiences) {
            if (type in experience.successfulActions) success++
            seen++
        }
        return if (seen == 0) 0.5f else success.toFloat() / seen.toFloat()
    }

    @Synchronized
    fun recentReward(): Float {
        if (experiences.isEmpty()) return 0f
        var total = 0f
        var count = 0
        val iterator = experiences.descendingIterator()
        while (iterator.hasNext() && count < 8) {
            total += iterator.next().reward
            count++
        }
        return if (count == 0) 0f else total / count
    }

    @Synchronized
    fun size(): Int = experiences.size
}

 
data class CausalRelation(
    val cause: String,
    val effect: String,
    val strength: Float,
    val observations: Int = 1
)

data class ReasoningConclusion(
    val hypothesis: String,
    val evidence: List<String>,
    val confidence: Float,
    val recommendedActions: List<ActionType>
)

class CausalReasoningGraph(private val capacity: Int = 256) {
    private val relations = ArrayDeque<CausalRelation>()

    @Synchronized
    fun observe(cause: String, effect: String, outcome: Float) {
        val existing = relations.firstOrNull { it.cause == cause && it.effect == effect }
        if (existing != null) {
            val updated = existing.copy(
                strength = (existing.strength * 0.75f + outcome * 0.25f).coerceIn(-1f, 1f),
                observations = existing.observations + 1
            )
            relations.remove(existing)
            relations.addLast(updated)
        } else {
            if (relations.size >= capacity) relations.removeFirst()
            relations.addLast(CausalRelation(cause, effect, outcome.coerceIn(-1f, 1f)))
        }
    }

    @Synchronized
    fun infer(observation: AgentObservation, state: AgentState): ReasoningConclusion {
        val evidence = mutableListOf<String>()
        val actions = mutableListOf<ActionType>()
        var confidence = state.confidence * 0.5f

        if (observation.tokenCount > 0) {
            evidence += "input_present"
            actions += ActionType.CONSOLIDATE_MEMORY
            actions += ActionType.RUN_INFERENCE
            confidence += 0.15f
        }

        if (observation.spatialSurfaceCount > 0 || state.novelty > 0.25f) {
            evidence += "environment_changed"
            actions += ActionType.ANALYZE_SPATIAL
            confidence += 0.12f
        }

        if (observation.economicOpportunity) {
            evidence += "economic_signal_present"
            actions += ActionType.EVALUATE_ECONOMIC_TASK
            confidence += 0.08f
        }

        val positiveRelations = relations.filter { it.strength > 0.45f }.takeLast(3)
        positiveRelations.forEach {
            evidence += it.cause + "->" + it.effect
            confidence += 0.03f
        }

        return ReasoningConclusion(
            hypothesis = if (evidence.isEmpty()) "no_actionable_signal" else "current_state_requires_adaptive_response",
            evidence = evidence,
            confidence = confidence.coerceIn(0f, 1f),
            recommendedActions = actions.distinct()
        )
    }
}

class HoloIntelligenceCore(private val persistence: PersistentCognitiveMemory? = null) {
    private val experienceMemory = AdaptiveExperienceMemory()
    private val causalGraph = CausalReasoningGraph()
    private var state = AgentState()
    private var previousObservation: AgentObservation? = null
    private val goalPlanner = GoalDecompositionPlanner()
    private var activeMultiStepPlan = MultiStepPlan("", emptyList())

    init { persistence?.let { state = it.loadState(); experienceMemory.restore(it.loadExperiences()); causalGraph.restore(it.loadRelations()) } }

    @Synchronized
    fun currentState(): AgentState = state

    @Synchronized
    fun experienceCount(): Int = experienceMemory.size()

    fun runCycle(
        goal: AgentGoal,
        observation: AgentObservation,
        executor: (PlannedAction) -> ActionResult
    ): IntelligenceCycleResult {
        val before = synchronized(this) {
            val novelty = estimateNovelty(observation, previousObservation)
            state = state.copy(
                activeGoal = goal,
                novelty = novelty,
                confidence = clamp01(state.confidence),
                stability = clamp01(state.stability)
            )
            state
        }

        val reasoning = causalGraph.infer(observation, before)
        activeMultiStepPlan = goalPlanner.decompose(goal, observation, reasoning, before)
        val plan = buildPlan(before, observation, reasoning, activeMultiStepPlan)
        val results = plan.map { action ->
            try {
                executor(action)
            } catch (t: Throwable) {
                ActionResult(action, false, -1f, t.message ?: t::class.java.simpleName)
            }
        }

        val reward = evaluateReward(goal, observation, results)
        activeMultiStepPlan = goalPlanner.advance(activeMultiStepPlan, results)

        val successfulActions = results
            .filter { it.success }
            .mapTo(mutableSetOf()) { it.action.type }

        causalGraph.observe(
            cause = "cycle_" + before.cycle,
            effect = "reward_" + String.format("%.2f", reward),
            outcome = reward.coerceIn(-1f, 1f)
        )

        experienceMemory.append(
            AdaptiveExperienceMemory.Experience(
                cycle = before.cycle + 1,
                observation = observation,
                reward = reward,
                successfulActions = successfulActions
            )
        )

        val successRatio = if (results.isEmpty()) 0f else {
            results.count { it.success }.toFloat() / results.size.toFloat()
        }

        val after = synchronized(this) {
            val rewardTrend = experienceMemory.recentReward()
            val nextConfidence = clamp01(
                state.confidence * 0.70f +
                    successRatio * 0.20f +
                    normalizeReward(rewardTrend) * 0.10f
            )
            val nextStability = clamp01(
                state.stability * 0.75f +
                    (1f - abs(reward - state.lastReward).coerceAtMost(1f)) * 0.25f
            )
            state = state.copy(
                cycle = state.cycle + 1,
                confidence = nextConfidence,
                stability = nextStability,
                lastReward = reward,
                activeGoal = goal
            )
            previousObservation = observation
            state
        }

        persistence?.save(state, experienceMemory.snapshot(), causalGraph.snapshot())
        return IntelligenceCycleResult(before, after, plan, activeMultiStepPlan, results, reward)
    }

    private fun buildPlan(
        state: AgentState,
        observation: AgentObservation,
        reasoning: ReasoningConclusion,
        multiStepPlan: MultiStepPlan
    ): List<PlannedAction> {
        val candidates = mutableListOf<PlannedAction>()
        val activeStep = multiStepPlan.steps.getOrNull(multiStepPlan.currentStepIndex)
        activeStep?.let { candidates += scored(it.action, "Bước ${it.id}: ${it.description}", it.priority + multiStepPlan.confidence * .15f, it.id) }

        if (observation.tokenCount > 0) {
            candidates += scored(
                ActionType.CONSOLIDATE_MEMORY,
                "Có dữ liệu đầu vào cần hợp nhất vào trạng thái dài hạn",
                0.82f + state.novelty * 0.12f
            )
            candidates += scored(
                ActionType.RUN_INFERENCE,
                "Cần tạo tín hiệu tính toán từ đầu vào hiện tại",
                0.78f + state.activeGoalPriority() * 0.10f
            )
        }

        if (observation.spatialSurfaceCount > 0 || state.novelty > 0.25f) {
            candidates += scored(
                ActionType.ANALYZE_SPATIAL,
                "Môi trường có tín hiệu không gian cần kiểm tra",
                0.64f + state.novelty * 0.16f
            )
        }

        candidates += scored(
            ActionType.SHARE_STATE,
            "Đồng bộ dấu vết trạng thái sau chu kỳ",
            0.46f + state.confidence * 0.12f
        )

        if (observation.economicOpportunity) {
            candidates += scored(
                ActionType.EVALUATE_ECONOMIC_TASK,
                "Có cơ hội kinh tế nhưng chỉ thực thi khi lợi ích vượt chi phí",
                0.52f + state.activeGoalPriority() * 0.10f
            )
        }

        if (candidates.isEmpty()) {
            candidates += PlannedAction(ActionType.IDLE, "Không có hành động hữu ích", 0.1f)
        }

        return candidates
            .sortedByDescending { it.expectedUtility }
            .take(5)
    }

    private fun scored(type: ActionType, reason: String, baseUtility: Float, stepId: String? = null): PlannedAction {
        val learned = experienceMemory.actionSuccessRate(type)
        val utility = clamp01(baseUtility * 0.75f + learned * 0.25f)
        return PlannedAction(type, reason, utility, stepId)
    }

    private fun evaluateReward(
        goal: AgentGoal,
        observation: AgentObservation,
        results: List<ActionResult>
    ): Float {
        if (results.isEmpty()) return -0.25f
        val actionSignal = results.sumOf { it.signal.toDouble() }.toFloat() / results.size.toFloat()
        val successRatio = results.count { it.success }.toFloat() / results.size.toFloat()
        val latencyScore = when {
            observation.inferenceLatencyNs <= 0L -> 0f
            observation.inferenceLatencyNs < 1_000_000L -> 1f
            observation.inferenceLatencyNs < 10_000_000L -> 0.5f
            else -> 0.1f
        }
        val goalWeight = clamp01(goal.priority)
        return (
            actionSignal * 0.35f +
                successRatio * 0.35f +
                latencyScore * 0.15f +
                (if (observation.cycleSucceeded) 1f else 0f) * 0.15f
            ) * (0.75f + goalWeight * 0.25f)
    }

    private fun estimateNovelty(
        current: AgentObservation,
        previous: AgentObservation?
    ): Float {
        previous ?: return 1f
        var delta = 0f
        delta += min(1f, abs(current.tokenCount - previous.tokenCount) / 32f)
        delta += abs(current.memorySimilarity - previous.memorySimilarity)
        delta += min(1f, abs(current.spatialSurfaceCount - previous.spatialSurfaceCount) / 8f)
        if (current.economicOpportunity != previous.economicOpportunity) delta += 1f
        return clamp01(delta / 4f)
    }

    private fun AgentState.activeGoalPriority(): Float = activeGoal?.priority?.let(::clamp01) ?: 0.5f

    private fun normalizeReward(value: Float): Float = clamp01((value + 1f) / 2f)

    private fun clamp01(value: Float): Float = max(0f, min(1f, value))
}
