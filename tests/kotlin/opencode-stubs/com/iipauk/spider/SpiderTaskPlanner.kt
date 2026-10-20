package com.iipauk.spider

/** Signature-only stand-in for the Agent Core planner interface. */
interface SpiderTaskPlanner {
    fun proposePlan(task: SpiderAgentTask): List<SpiderPlanStep>
}
