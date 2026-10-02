package com.holoagent

data class GoalStep(
    val id:String,
    val description:String,
    val action:ActionType,
    val dependsOn:List<String> = emptyList(),
    val priority:Float = .5f
)
data class MultiStepPlan(
    val goalId:String,
    val steps:List<GoalStep>,
    val currentStepIndex:Int = 0,
    val confidence:Float = .5f
)
class GoalDecompositionPlanner {
 fun decompose(goal:AgentGoal, observation:AgentObservation, reasoning:ReasoningConclusion, state:AgentState):MultiStepPlan {
  val s=mutableListOf<GoalStep>()
  if(observation.tokenCount>0)s+=GoalStep("observe","Tiếp nhận tín hiệu và hợp nhất trí nhớ",ActionType.CONSOLIDATE_MEMORY,priority=.95f)
  if(observation.tokenCount>0)s+=GoalStep("infer","Suy luận từ tín hiệu hiện tại và kinh nghiệm",ActionType.RUN_INFERENCE,listOf("observe"),.90f)
  if(observation.spatialSurfaceCount>0||state.novelty>.25f)s+=GoalStep("spatial","Phân tích thay đổi môi trường",ActionType.ANALYZE_SPATIAL,listOf("infer"),.78f)
  if(observation.economicOpportunity)s+=GoalStep("economic","Đánh giá tác vụ kinh tế",ActionType.EVALUATE_ECONOMIC_TASK,listOf("infer"),.70f)
  s+=GoalStep("sync","Đồng bộ trạng thái và kết quả",ActionType.SHARE_STATE,s.map{it.id},.55f)
  return MultiStepPlan(goal.id,s,0,(reasoning.confidence*.7f+state.confidence*.3f).coerceIn(0f,1f))
 }
 fun advance(plan:MultiStepPlan,results:List<ActionResult>):MultiStepPlan {
  val current=plan.steps.getOrNull(plan.currentStepIndex)?:return plan
  val ok=results.any{it.action.stepId==current.id&&it.success}
  return if(ok&&plan.currentStepIndex<plan.steps.lastIndex)plan.copy(currentStepIndex=plan.currentStepIndex+1) else plan
 }
}