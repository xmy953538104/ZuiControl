package com.zui.zuicontrol

import android.content.Intent
import android.content.pm.PackageManager
import java.io.File

/** UPERF_CONFIGURABLE_APP: docs/UPERF_CONFIGURABLE_APP.md, mirrored by the daemon. */
internal object UperfAppPolicy {
    val allowedRoots = listOf("/data/app/", "/system/preinstall/")
    val excludedPackages = setOf("android", "com.android.systemui")
    private val pathCharacters = Regex("[A-Za-z0-9_./+=~-]+")

    fun accepts(pkg: String, paths: List<String>, canonical: List<String>, launchable: Boolean, qualifiedSystemScene: Boolean = false): Boolean =
        PackageNames.isValid(pkg) && pkg !in excludedPackages && launchable &&
            paths.isNotEmpty() && paths.size == canonical.size &&
            paths.zip(canonical).all { (path, resolved) ->
                path == resolved && pathCharacters.matches(path) && path.endsWith(".apk") &&
                    "//" !in path && "/./" !in path && "/../" !in path &&
                    (allowedRoots + if (qualifiedSystemScene) listOf("/system/app/", "/system/priv-app/") else emptyList())
                        .any { path.startsWith(it) && path.length > it.length + 4 }
            }

    @Suppress("DEPRECATION")
    fun isConfigurable(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (!PackageNames.isValid(pkg) || pkg in excludedPackages) return false
        val app = pm.getApplicationInfo(pkg, 0)
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val isHome = home != null && home.packageName == pkg && home.enabled && home.exported
        val activity = if (isHome) home else pm.resolveActivity(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0)?.activityInfo
        accepts(pkg, paths, paths.map { File(it).canonicalPath },
            app.enabled && activity != null && activity.enabled && activity.exported &&
                activity.packageName == pkg,
            pkg == "com.zui.zuicontrol" || isHome) && paths.all { File(it).isFile }
    }.getOrDefault(false)
}
