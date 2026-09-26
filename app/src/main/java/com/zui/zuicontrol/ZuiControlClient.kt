package com.zui.zuicontrol

import android.zui.ZuiControlManager
import android.os.Process

object ZuiControlClient {
    private const val MODE_DISPLAY_ONLY = "DISPLAY_ONLY"
    private const val PER_USER_RANGE = 100_000

    data class Reply(
        val ok: Boolean,
        val text: String,
    )

    fun setCurrentSceneDisplayHz(context: android.content.Context, displayHz: Int, scene: String = currentSceneText()): Reply =
        policy(context, "refresh", stateValue(scene, "policyScenePackage").orEmpty(), "FOREGROUND", value = displayHz, scene = scene)

    fun setPackageDisplayHz(
        context: android.content.Context,
        packageName: String,
        displayHz: Int,
        userId: Int = currentUserId(),
    ): Reply {
        require(userId == currentUserId())
        return policy(context, "refresh", packageName, "APP", value = displayHz)
    }

    fun removePackageProfile(context: android.content.Context, packageName: String, userId: Int = currentUserId()): Reply {
        require(userId == currentUserId())
        return policy(context, "delete", packageName, "APP")
    }

    fun currentDisplayHz(): Int? {
        return stateInt("targetDisplayHz")
    }

    fun setGpuRange(context: android.content.Context, pkg: String, range: GpuRanges.Range?): Reply =
        if (range == null) policy(context, "gpuDefault", pkg, "APP") else policy(context, "gpu", pkg, "APP", min = range.min, max = range.max)

    fun setGlobalGpuRange(context: android.content.Context, mode: String, range: GpuRanges.Range): Reply =
        policy(context, "defaultGpu", "", "GLOBAL", mode = mode, min = range.min, max = range.max)

    fun sendPolicy(context: android.content.Context, action: String, pkg: String, scope: String,
                   value: Int = 0, mode: String = "", min: Int = 0, max: Int = 0,
                   scene: String = currentSceneText()): String {
        val generation = stateValue(scene, "policyGeneration")?.toLongOrNull() ?: 0
        check(generation > 0) { "应用策略尚未迁移就绪，请刷新状态" }
        val payload = org.json.JSONObject().put("action", action).put("packageName", pkg)
            .put("scope", scope).put("userId", currentUserId()).put("generation", generation)
            .put("sceneGeneration", stateValue(scene, "policySceneGeneration")!!.toLong())
            .put("scenePackage", stateValue(scene, "policyScenePackage").orEmpty())
            .put("value", value).put("mode", mode).put("min", min).put("max", max)
        return ZuiControlRequest.send(context, "policy", mode = java.util.Base64.getEncoder()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun policy(context: android.content.Context, action: String, pkg: String, scope: String,
                       value: Int = 0, mode: String = "", min: Int = 0, max: Int = 0,
                       scene: String = currentSceneText()): Reply = try {
        val id = sendPolicy(context, action, pkg, scope, value, mode, min, max, scene)
        val ack = ZuiControlRequest.awaitTerminalAck(context, id)
        Reply(ack.succeeded, ack.detail)
    } catch (e: Exception) { Reply(false, e.message.orEmpty()) }

    fun editableDisplayHz(): Int? {
        return stateInt("editableDisplayHz")?.takeIf { it > 0 }
    }

    fun stateText(): String {
        return call { it.getState() }.text
    }

    fun currentSceneText(): String = call { it.getCurrentScene() }.text

    fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    internal fun stateValue(state: String, key: String): String? =
        state.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')

    private fun stateInt(key: String): Int? {
        val state = call { it.getState() }
        if (!state.ok) {
            return null
        }
        return stateValue(state.text, key)?.toIntOrNull()
    }

    fun notifyControlRequest(requestId: String, requestSha256: String): Reply {
        return call { it.notifyControlRequest(requestId, requestSha256) }
    }

    private fun call(block: (ZuiControlManager) -> String): Reply {
        return try {
            val manager = ZuiControlManager.get()
                ?: return Reply(false, "zui_control service unavailable")
            val reply = block(manager)
            Reply(replyIsOk(reply), reply)
        } catch (e: Throwable) {
            Reply(false, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    internal fun replyIsOk(reply: String): Boolean =
        reply.lineSequence().firstOrNull()?.trim() == "ok=1"
}
