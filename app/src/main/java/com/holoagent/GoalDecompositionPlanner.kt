package com.holoagent

data class GoalStep(val id: String, val description: String, val action: ActionType, val dependsOn: List<String> = emptyList(), val priority: Float = 0.5f)
data class MultiStepPlan(val goalId: String, val steps: List<GoalStep>, val currentStepIndex: Int = 0, val confidence: Float = 0.5f)

class GoalDecompositionPlanner {
    fun decompose(goal: AgentGoal, observation: AgentObservation, reasoning: ReasoningConclusion, state: AgentState): MultiStepPlan = buildPlan(goal, observation, reasoning, state, 0)

    fun replan(plan: MultiStepPlan, observation: AgentObservation, reasoning: ReasoningConclusion, state: AgentState, previousResults: List<ActionResult> = emptyList()): MultiStepPlan {
        if (plan.steps.isEmpty()) return decompose(AgentGoal(plan.goalId, "adaptive recovery", state.activeGoal?.priority ?: 0.5f), observation, reasoning, state)
        val failed = previousResults.filter { !it.success }
        val failedTypes = failed.map { it.action.type }.toSet()
        val failedStepIds = failed.mapNotNull { it.action.stepId }.toSet()
        val remaining = plan.steps.drop(plan.currentStepIndex)
        val rebuilt = mutableListOf<GoalStep>()
        if (failed.isNotEmpty()) {
            val failedStep = remaining.firstOrNull { it.id in failedStepIds }
            if (failedStep != null) rebuilt += GoalStep("recover_" + failedStep.id, "Khôi phục bước " + failedStep.id + " sau lỗi: " + failed.first().detail, recoveryAction(failedStep.action, observation), priority = 1.0f)
        }
        remaining.forEach { step ->
            if (step.id in failedStepIds && step.action in failedTypes) return@forEach
            if (step.action == ActionType.CONSOLIDATE_MEMORY && observation.tokenCount <= 0) return@forEach
            if (step.action == ActionType.ANALYZE_SPATIAL && observation.spatialSurfaceCount <= 0 && state.novelty <= 0.25f) return@forEach
            if (step.action == ActionType.EVALUATE_ECONOMIC_TASK && !observation.economicOpportunity) return@forEach
            rebuilt += step.copy(dependsOn = rebuilt.map { it.id })
        }
        if (rebuilt.isEmpty()) {
            rebuilt += GoalStep("recover_infer", "Tạo lại tín hiệu suy luận sau thay đổi hoặc lỗi", ActionType.RUN_INFERENCE, priority = .70f)
            rebuilt += GoalStep("recover_sync", "Đồng bộ trạng thái sau khôi phục", ActionType.SHARE_STATE, listOf("recover_infer"), .55f)
        }
        val confidencePenalty = if (failed.isEmpty()) 0f else 0.15f
        return MultiStepPlan(plan.goalId, rebuilt, 0, (reasoning.confidence * .65f + state.confidence * .35f - confidencePenalty).coerceIn(0f, 1f))
    }

    fun advance(plan: MultiStepPlan, results: List<ActionResult>): MultiStepPlan {
        val current = plan.steps.getOrNull(plan.currentStepIndex) ?: return plan
        val currentResult = results.firstOrNull { it.action.stepId == current.id }
        return when {
            currentResult?.success == true -> plan.copy(currentStepIndex = plan.currentStepIndex + 1)
            currentResult?.success == false -> plan.copy(confidence = (plan.confidence * .65f).coerceIn(0f, 1f))
            else -> plan
        }
    }

    private fun recoveryAction(action: ActionType, observation: AgentObservation): ActionType = when (action) {
        ActionType.CONSOLIDATE_MEMORY -> if (observation.tokenCount > 0) ActionType.CONSOLIDATE_MEMORY else ActionType.RUN_INFERENCE
        ActionType.RUN_INFERENCE -> ActionType.RUN_INFERENCE
        ActionType.ANALYZE_SPATIAL -> if (observation.spatialSurfaceCount > 0) ActionType.ANALYZE_SPATIAL else ActionType.RUN_INFERENCE
        ActionType.EVALUATE_ECONOMIC_TASK -> if (observation.economicOpportunity) ActionType.EVALUATE_ECONOMIC_TASK else ActionType.SHARE_STATE
        ActionType.SHARE_STATE -> ActionType.SHARE_STATE
        ActionType.IDLE -> ActionType.RUN_INFERENCE
    }

    private fun buildPlan(goal: AgentGoal, observation: AgentObservation, reasoning: ReasoningConclusion, state: AgentState, startIndex: Int): MultiStepPlan {
        val steps = mutableListOf<GoalStep>()
        if (observation.tokenCount > 0) {
            steps += GoalStep("observe", "Tiếp nhận tín hiệu và hợp nhất trí nhớ", ActionType.CONSOLIDATE_MEMORY, priority = .95f)
            steps += GoalStep("infer", "Suy luận từ tín hiệu hiện tại và kinh nghiệm", ActionType.RUN_INFERENCE, listOf("observe"), .90f)
        }
        if (observation.spatialSurfaceCount > 0 || state.novelty > .25f) steps += GoalStep("spatial", "Phân tích thay đổi môi trường", ActionType.ANALYZE_SPATIAL, steps.map { it.id }, .78f)
        if (observation.economicOpportunity) steps += GoalStep("economic", "Đánh giá tác vụ kinh tế", ActionType.EVALUATE_ECONOMIC_TASK, steps.map { it.id }, .70f)
        steps += GoalStep("sync", "Đồng bộ trạng thái và kết quả", ActionType.SHARE_STATE, steps.map { it.id }, .55f)
        return MultiStepPlan(goal.id, steps, startIndex.coerceIn(0, maxOf(0, steps.lastIndex)), (reasoning.confidence * .7f + state.confidence * .3f).coerceIn(0f, 1f))
    }
}