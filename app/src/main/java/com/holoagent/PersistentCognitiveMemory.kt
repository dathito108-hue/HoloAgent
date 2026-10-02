package com.holoagent
import android.content.Context
import android.content.SharedPreferences
data class PersistedExperience(val cycle:Long,val observation:AgentObservation,val reward:Float,val successfulActions:Set<ActionType>)
data class PersistedCausalRelation(val cause:String,val effect:String,val strength:Float,val observations:Int)
class PersistentCognitiveMemory(context:Context){
 private val p:SharedPreferences=context.applicationContext.getSharedPreferences("holo_cognitive_memory_v1",Context.MODE_PRIVATE)
 private fun e(s:String)=android.util.Base64.encodeToString(s.toByteArray(),android.util.Base64.NO_WRAP)
 private fun d(s:String)=String(android.util.Base64.decode(s,android.util.Base64.NO_WRAP))
 fun save(state:AgentState,ex:List<PersistedExperience>,rel:List<PersistedCausalRelation>){
  val st=listOf(state.cycle,state.confidence,state.novelty,state.stability,state.lastReward,state.activeGoal?.id?:"",state.activeGoal?.description?:"",state.activeGoal?.priority?:0f).joinToString("|",::e)
  val xs=ex.joinToString("\n"){listOf(it.cycle,it.observation.tokenCount,it.observation.memorySimilarity,it.observation.spatialSurfaceCount,it.observation.inferenceLatencyNs,it.observation.economicOpportunity,it.observation.cycleSucceeded,it.reward,it.successfulActions.joinToString(","){a->a.name}).joinToString("|",::e)}
  val rs=rel.joinToString("\n"){listOf(it.cause,it.effect,it.strength,it.observations).joinToString("|",::e)}
  p.edit().putString("state",st).putString("experiences",xs).putString("relations",rs).apply()
 }
 fun loadState():AgentState=runCatching{val q=p.getString("state",null)?:return@runCatching AgentState();val a=q.split("|").map(::d);AgentState(a[0].toLong(),a[1].toFloat(),a[2].toFloat(),a[3].toFloat(),a[4].toFloat(),if(a[5].isEmpty())null else AgentGoal(a[5],a[6],a[7].toFloat()))}.getOrDefault(AgentState())
 fun loadExperiences():List<PersistedExperience>=p.getString("experiences","").orEmpty().lines().mapNotNull{v->runCatching{val a=v.split("|").map(::d);PersistedExperience(a[0].toLong(),AgentObservation(a[1].toInt(),a[2].toFloat(),a[3].toInt(),a[4].toLong(),a[5].toBoolean(),a[6].toBoolean()),a[7].toFloat(),a[8].split(",").filter(String::isNotBlank).mapNotNull{n->runCatching{ActionType.valueOf(n)}.getOrNull()}.toSet())}.getOrNull()}
 fun loadRelations():List<PersistedCausalRelation>=p.getString("relations","").orEmpty().lines().mapNotNull{v->runCatching{val a=v.split("|").map(::d);PersistedCausalRelation(a[0],a[1],a[2].toFloat(),a[3].toInt())}.getOrNull()}
}