package com.zui.zuicontrol

import android.zui.ZuiControlManager

object ZuiControlClient {
    private const val MODE_DISPLAY_ONLY = "DISPLAY_ONLY"

    data class Reply(
        val ok: Boolean,
        val text: String,
    )

    fun setCurrentSceneDisplayHz(displayHz: Int): Reply {
        return call {
            it.setCurrentSceneProfile(displayHz, 0, MODE_DISPLAY_ONLY)
        }
    }

    fun setPackageDisplayHz(packageName: String, displayHz: Int, userId: Int = 0): Reply {
        return call {
            it.setProfile(packageName, userId, displayHz, 0, MODE_DISPLAY_ONLY)
        }
    }

    fun removePackageProfile(packageName: String, userId: Int = 0): Reply {
        return call {
            it.removeProfile(packageName, userId)
        }
    }

    fun currentDisplayHz(): Int? {
        val state = call { it.getState() }
        if (!state.ok) {
            return null
        }
        return state.text.lineSequence()
            .firstOrNull { it.startsWith("targetDisplayHz=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
    }

    fun stateText(): String {
        return call { it.getState() }.text
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
