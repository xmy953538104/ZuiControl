package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test

class UperfAppPolicyTest {
    private fun accepts(paths: List<String>, canonical: List<String> = paths,
                        launchable: Boolean = true, pkg: String = "com.example.game") =
        UperfAppPolicy.accepts(pkg, paths, canonical, launchable)

    @Test fun allPathsMustBeQualifiedCanonicalApks() {
        for (root in UperfAppPolicy.allowedRoots) {
            assertTrue(accepts(listOf(root + "game/base.apk", root + "game/split_config.arm64.apk")))
        }
        for (bad in listOf("/system/app/game/base.apk", "/system/priv-app/game/base.apk",
            "/vendor/app/game/base.apk", "/data/application/game.apk", "/data/app/../x.apk",
            "/data/app/./x.apk", "/data/app//x.apk", "/data/app/space name.apk",
            "/data/app/x.apk/extra", "/data/app/.apk", "", "package:/data/app/x.apk")) {
            assertFalse(bad, accepts(listOf(bad)))
            assertFalse(bad, accepts(listOf("/data/app/game/base.apk", bad)))
        }
        assertFalse(accepts(emptyList()))
        assertFalse(accepts(listOf("/data/app/base.apk"), emptyList()))
        assertFalse(accepts(listOf("/data/app/link.apk"), listOf("/system/app/core.apk")))
    }

    @Test fun onlyLaunchableNonCorePackageNames() {
        val paths = listOf("/system/preinstall/Calculator/Calculator.apk")
        assertFalse(accepts(paths, launchable = false))
        for (pkg in UperfAppPolicy.excludedPackages + listOf("", "single", "a..b", ".a.b", "a.b.", "a/b.c")) {
            assertFalse(pkg, accepts(paths, pkg = pkg))
        }
    }
}
