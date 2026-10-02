package com.holoagent

data class GoalStep(
    val id: String,
    val description: String,
    val action: ActionType,
    val dependsOn: List<String> = emptyList(),
    val priority: Float = 0.5f
)

data class MultiStepPlan(
    val goalId: String,
    val steps: List<GoalStep>,
    val currentStepIndex: Int = 0,
    val confidence: Float = 0.5f
)

class GoalDecompositionPlanner {
    fun decompose(
        goal: AgentGoal,
        observation: AgentObservation,
        reasoning: ReasoningConclusion,
        state: AgentState
    ): MultiStepPlan {
        return buildPlan(goal, observation, reasoning, state, 0)
    }

    fun replan(
        plan: MultiStepPlan,
        observation: AgentObservation,
        reasoning: ReasoningConclusion,
        state: AgentState
    ): MultiStepPlan {
        if (plan.steps.isEmpty()) return decompose(
            AgentGoal(plan.goalId, "adaptive recovery", state.activeGoal?.priority ?: 0.5f),
            observation,
            reasoning,
            state
        )

        val remaining = plan.steps.drop(plan.currentStepIndex)
        val actionable = remaining.map { it.action }.toSet()
        val rebuilt = mutableListOf<GoalStep>()

        if (ActionType.CONSOLIDATE_MEMORY in actionable && observation.tokenCount <= 0) {
            // No input: memory consolidation is no longer actionable.
        } else if (ActionType.CONSOLIDATE_MEMORY in actionable) {
            rebuilt += GoalStep("observe", "Tiếp nhận tín hiệu và hợp nhất trí nhớ", ActionType.CONSOLIDATE_MEMORY, priority = .95f)
        }

        if (ActionType.RUN_INFERENCE in actionable) {
            rebuilt += GoalStep("infer", "Suy luận lại từ trạng thái mới", ActionType.RUN_INFERENCE, rebuilt.map { it.id }, .90f)
        }

        if (ActionType.ANALYZE_SPATIAL in actionable || observation.spatialSurfaceCount > 0 || state.novelty > .25f) {
            rebuilt += GoalStep("spatial", "Phân tích thay đổi môi trường", ActionType.ANALYZE_SPATIAL, rebuilt.map { it.id }, .78f)
        }

        if (ActionType.EVALUATE_ECONOMIC_TASK in actionable && observation.economicOpportunity) {
            rebuilt += GoalStep("economic", "Đánh giá lại tác vụ kinh tế", ActionType.EVALUATE_ECONOMIC_TASK, rebuilt.map { it.id }, .70f)
        }

        if (ActionType.SHARE_STATE in actionable || rebuilt.isNotEmpty()) {
            rebuilt += GoalStep("sync", "Đồng bộ trạng thái và kết quả", ActionType.SHARE_STATE, rebuilt.map { it.id }, .55f)
        }

        return if (rebuilt.isEmpty()) {
            MultiStepPlan(plan.goalId, listOf(
                GoalStep("recover", "Khôi phục từ tín hiệu mới", ActionType.RUN_INFERENCE, priority = .65f),
                GoalStep("sync", "Đồng bộ trạng thái", ActionType.SHARE_STATE, listOf("recover"), .55f)
            ), 0, (reasoning.confidence * .7f + state.confidence * .3f).coerceIn(0f, 1f))
        } else {
            MultiStepPlan(
                goalId = plan.goalId,
                steps = rebuilt,
                currentStepIndex = 0,
                confidence = (reasoning.confidence * .7f + state.confidence * .3f).coerceIn(0f, 1f)
            )
        }
    }

    fun advance(plan: MultiStepPlan, results: List<ActionResult>): MultiStepPlan {
        val current = plan.steps.getOrNull(plan.currentStepIndex) ?: return plan
        val currentResult = results.firstOrNull { it.action.stepId == current.id }

        return when {
            currentResult?.success == true && plan.currentStepIndex < plan.steps.lastIndex ->
                plan.copy(currentStepIndex = plan.currentStepIndex + 1)
            currentResult?.success == false ->
                plan.copy(confidence = (plan.confidence * .75f).coerceIn(0f, 1f))
            else -> plan
        }
    }

    private fun buildPlan(
        goal: AgentGoal,
        observation: AgentObservation,
        reasoning: ReasoningConclusion,
        state: AgentState,
        startIndex: Int
    ): MultiStepPlan {
        val steps = mutableListOf<GoalStep>()

        if (observation.tokenCount > 0) {
            steps += GoalStep("observe", "Tiếp nhận tín hiệu và hợp nhất trí nhớ", ActionType.CONSOLIDATE_MEMORY, priority = .95f)
            steps += GoalStep("infer", "Suy luận từ tín hiệu hiện tại và kinh nghiệm", ActionType.RUN_INFERENCE, listOf("observe"), .90f)
        }

        if (observation.spatialSurfaceCount > 0 || state.novelty > .25f) {
            steps += GoalStep("spatial", "Phân tích thay đổi môi trường", ActionType.ANALYZE_SPATIAL, steps.map { it.id }, .78f)
        }

        if (observation.economicOpportunity) {
            steps += GoalStep("economic", "Đánh giá tác vụ kinh tế", ActionType.EVALUATE_ECONOMIC_TASK, steps.map { it.id }, .70f)
        }

        steps += GoalStep("sync", "Đồng bộ trạng thái và kết quả", ActionType.SHARE_STATE, steps.map { it.id }, .55f)

        return MultiStepPlan(
            goalId = goal.id,
            steps = steps,
            currentStepIndex = startIndex.coerceIn(0, maxOf(0, steps.lastIndex)),
            confidence = (reasoning.confidence * .7f + state.confidence * .3f).coerceIn(0f, 1f)
        )
    }
}
