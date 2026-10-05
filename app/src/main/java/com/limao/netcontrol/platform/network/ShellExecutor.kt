package com.limao.netcontrol.platform.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Shell 命令执行结果。调用方必须检查 success / exitCode，禁止假设成功。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val success: Boolean get() = exitCode == 0
}

/** Shell 执行器抽象：Root 与 Shizuku 共用同一套命令逻辑。 */
interface ShellExecutor {
    suspend fun exec(command: String, timeoutMs: Long = 15000): ShellResult
}

/** 通过 `su -c` 执行命令的 Root 执行器。 */
class RootShellExecutor : ShellExecutor {
    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start()
                val p = process
                // 二、5 修复：用 waitFor(timeout) 实现真正的超时。
                // withTimeout 包住阻塞的 readText()/waitFor() 时，协程无法在挂起点取消，
                // su 授权弹窗无人点会一直卡住。用带超时的 waitFor，超时后强制杀进程。
                val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    try { p.destroyForcibly() } catch (_: Exception) {}
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    return@withContext ShellResult(-1, "", "timeout after ${timeoutMs}ms")
                }
                val stdout = try { p.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                val stderr = try { p.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                val code = p.exitValue()
                ShellResult(code, stdout.trim(), stderr.trim())
            } catch (e: Exception) {
                try { process?.destroyForcibly() } catch (_: Exception) {}
                Log.e(TAG, "root exec failed: $command", e)
                ShellResult(-1, "", e.message ?: "exception")
            } finally {
                try { process?.destroy() } catch (_: Exception) {}
            }
        }

    companion object {
        private const val TAG = "RootShell"
    }
}

/** 通过 Shizuku.newProcess 执行命令的执行器。 */
class ShizukuShellExecutor : ShellExecutor {
    // 反射 Method 只解析一次，避免每次 exec 都 getDeclaredMethod
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder()) {
                    return@withContext ShellResult(-1, "", "Shizuku binder 不可用")
                }
                // Shizuku 13.1.5+ 将 newProcess 私有化，用反射调用（Method 已缓存）
                val process = newProcessMethod.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as? Process
                    ?: return@withContext ShellResult(-1, "", "Shizuku.newProcess 返回 null")
                try {
                    val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!finished) {
                        try { process.destroyForcibly() } catch (_: Exception) {}
                        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        return@withContext ShellResult(-1, "", "timeout after ${timeoutMs}ms")
                    }
                    val stdout = try { process.inputStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    val stderr = try { process.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                    ShellResult(process.exitValue(), stdout.trim(), stderr.trim())
                } finally {
                    try { process.destroy() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "shizuku exec failed: $command", e)
                ShellResult(-1, "", e.message ?: "exception")
            }
        }

    companion object {
        private const val TAG = "ShizukuShell"
    }
}
