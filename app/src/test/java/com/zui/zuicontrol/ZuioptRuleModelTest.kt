package com.zui.zuicontrol
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class ZuioptRuleModelTest {
    private val raw="schema 3\nenabled true\nprofile Shared 2-6\nthread Shared Pool exact \"Worker\" selector=rank:2 40 7\nthread Shared Pool prefix \"Worker-\" selector=rank:2 30 7\npackage exact org.first.app Shared 100\npackage exact org.second.app Shared 90\n"
    @Test fun repeatedPrivateEditsDoNotAccumulateProfilesOrChangeSharedSibling() {
        val original=ZuioptRuleModel.parse(raw)
        var next=original.editApp("org.first.app"){it.copy(generalMask=setOf(7))}
        val privateId=next.mappings.first{it.packageName=="org.first.app"}.profile
        repeat(40){i->next=next.editApp("org.first.app"){it.copy(generalMask=setOf(if(i%2==0)6 else 7))}
            assertEquals(2,next.profiles.size);assertEquals(privateId,next.mappings.first{it.packageName=="org.first.app"}.profile)
            assertEquals(original.appProfile("org.second.app"),next.appProfile("org.second.app"))}
        val full=next.copy(profiles=next.profiles+(0 until 62).associate{"unused$it" to original.profiles.getValue("Shared")})
        assertEquals(64,full.editApp("org.first.app"){it.copy(generalMask=setOf(5))}.profiles.size)
    }
    @Test fun normalizedRoundTripAndSharedProfileIsolation() {
        val m=ZuioptRuleModel.parse(raw); val text=m.normalized()
        assertEquals(text,ZuioptRuleModel.parseNormalized(text).normalized())
        assertThrows(IllegalArgumentException::class.java) { ZuioptRuleModel.parseNormalized(raw) }
        val next=m.editApp("org.first.app") { it.copy(generalMask=setOf(7)) }
        assertEquals(m.profiles["Shared"], next.profiles["Shared"])
        assertEquals("Shared",next.mappings.first { it.packageName=="org.second.app" }.profile)
        assertEquals(setOf(7),next.profiles.getValue(next.mappings.first { it.packageName=="org.first.app" }.profile).generalMask)
        assertEquals("rank:2",next.profiles["Shared"]!!.rules.first().selector)
        assertEquals(text,m.normalized())
        assertEquals("USER_MODIFIED",next.provenance("org.first.app",m))
        assertEquals("UPSTREAM",next.provenance("org.second.app",m))
        val renamed=ZuioptRuleModel.parse(raw.replace("Shared","Alias").replace(" 40 "," 4 ").replace(" 30 "," 3 "))
        assertEquals("UPSTREAM",renamed.provenance("org.first.app",m))
        assertNull(m.provenance("org.unknown.app",m))
        for (scope in listOf("thread","task","process")) assertThrows(IllegalArgumentException::class.java) {
            ZuioptRuleModel.parse(raw.replace("rank:2",scope))
        }
    }
    @Test fun allFactoryProfilesAndMappingsRoundTrip() {
        val file=sequenceOf(File("../payload/system/etc/zuiopt/factory_rules.conf"),File("payload/system/etc/zuiopt/factory_rules.conf")).first { it.exists() }
        val m=ZuioptRuleModel.parse(file.readText());assertEquals(27,m.profiles.size);assertEquals(316,m.mappings.size)
        assertEquals(m,ZuioptRuleModel.parseNormalized(m.normalized()))
        assertEquals("c4daebc3385d7e21b2ffb25e6724cdca1487c0e9062328f343ac29f9b6eb6768",ZuioptRules.digest(m.normalized().toByteArray()))
    }
    @Test fun schema3RoundTripInheritanceAndSingleAppIsolation() {
        val m=ZuioptRuleModel.parse(raw.replace("schema 3","schema 3\nruntime_defaults 1 1").replace("profile Shared 2-6","profile Shared 2-6\nruntime Shared -1 -1 preset double"))
        assertEquals(m,ZuioptRuleModel.parseNormalized(m.normalized()))
        val next=m.editApp("org.first.app"){it.copy(runtime=it.runtime.copy(mode=2,rt=0))}
        assertEquals(-1,next.appProfile("org.second.app")!!.runtime.rt)
        assertEquals(0,next.appProfile("org.first.app")!!.runtime.rt)
        assertEquals(1,next.mode);assertEquals(1,next.rt)
        assertEquals("USER_MODIFIED",next.provenance("org.first.app",m))
        assertEquals("UPSTREAM",next.provenance("org.second.app",m))
        val resolved=m.copy(profiles=m.profiles.mapValues{it.value.copy(runtime=it.value.runtime.copy(mode=1,rt=1))})
        assertEquals("USER_MODIFIED",resolved.provenance("org.first.app",m))
        assertEquals("UPSTREAM",m.copy(mode=2,rt=0).provenance("org.first.app",m))
        assertThrows(IllegalArgumentException::class.java){ZuioptRuleModel.parse(m.normalized().replace("schema 3","schema 2"))}
        assertThrows(IllegalArgumentException::class.java){ZuioptRuleModel.parse(m.normalized().replace("preset double","explicit double"))}
        assertThrows(IllegalArgumentException::class.java){ZuioptRuleModel.parse(m.normalized().replace("runtime_defaults 1 1","runtime_defaults 1 2"))}
    }
}
