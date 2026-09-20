package com.zui.zuicontrol

import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

/** UPERF_CONFIGURABLE_APP: docs/UPERF_CONFIGURABLE_APP.md, mirrored by the daemon. */
internal object UperfAppPolicy {
    val allowedRoots = listOf("/data/app/", "/system/preinstall/")
    val excludedPackages = setOf("android", "com.android.systemui", "com.zui.zuicontrol")
    private val pathCharacters = Regex("[A-Za-z0-9_./+=~-]+")

    fun accepts(pkg: String, paths: List<String>, canonical: List<String>, launchable: Boolean): Boolean =
        PackageNames.isValid(pkg) && pkg !in excludedPackages && launchable &&
            paths.isNotEmpty() && paths.size == canonical.size &&
            paths.zip(canonical).all { (path, resolved) ->
                path == resolved && pathCharacters.matches(path) && path.endsWith(".apk") &&
                    "//" !in path && "/./" !in path && "/../" !in path &&
                    allowedRoots.any { path.startsWith(it) && path.length > it.length + 4 }
            }

    @Suppress("DEPRECATION")
    fun isConfigurable(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (!PackageNames.isValid(pkg) || pkg in excludedPackages) return false
        val app = pm.getApplicationInfo(pkg, 0)
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val activity = pm.resolveActivity(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0)?.activityInfo
        accepts(pkg, paths, paths.map { File(it).canonicalPath },
            app.enabled && activity != null && activity.enabled && activity.exported &&
                activity.packageName == pkg) && paths.all { File(it).isFile }
    }.getOrDefault(false)
}
