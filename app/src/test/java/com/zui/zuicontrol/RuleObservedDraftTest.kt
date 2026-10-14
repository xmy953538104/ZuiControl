package com.zui.zuicontrol
import org.junit.Assert.*
import org.junit.Test

class RuleObservedDraftTest {
    @Test fun ordinaryTemplateAndRecordingNamesStayLocalAndDoNotRecommendRt() {
        val profile=ZuioptRuleModel.Profile(setOf(0,1,2,3,4,5,6,7),emptyList())
        val base=ZuioptRuleModel(true,emptyMap(),emptyList(),mode=2,rt=1)
        val draft=RuleDraft("org.example.app",base,"g"+"a".repeat(24),null,profile)
        draft.addObservedThreads(listOf("RenderThread","Heavy Worker"))
        assertEquals(profile.runtime,draft.profile.runtime)
        assertEquals(-1,draft.profile.runtime.rt)
        assertTrue(draft.profile.rules.all{it.cpuMask==profile.generalMask && it.competitionClass !in setOf("C1","C2")})
        val result=ZuioptRuleModel.parseNormalized(draft.canonical())
        assertEquals(2,result.mode);assertEquals(1,result.rt)
        assertEquals(-1,result.appProfile("org.example.app")!!.runtime.rt)
        assertTrue(base.profiles.isEmpty())
        draft.addObservedThreads(listOf("RenderThread"));assertEquals(2,draft.profile.rules.size)
        assertThrows(IllegalArgumentException::class.java){draft.addObservedThreads(listOf("invalid\nname"))}
    }
}
