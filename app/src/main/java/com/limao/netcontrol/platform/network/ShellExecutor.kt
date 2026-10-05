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
            try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start()
                withTimeout(timeoutMs) {
                    val stdout = async { process.inputStream.bufferedReader().readText() }
                    val stderr = async { process.errorStream.bufferedReader().readText() }
                    val code = process.waitFor()
                    ShellResult(code, stdout.await().trim(), stderr.await().trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "root exec failed: $command", e)
                ShellResult(-1, "", e.message ?: "exception")
            }
        }

    companion object {
        private const val TAG = "RootShell"
    }
}

/** 通过 Shizuku.newProcess 执行命令的执行器。 */
class ShizukuShellExecutor : ShellExecutor {
    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder()) {
                    return@withContext ShellResult(-1, "", "Shizuku binder 不可用")
                }
                // Shizuku 13.1.5+ 将 newProcess 私有化，用反射调用
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
                val process = method.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as? Process
                    ?: return@withContext ShellResult(-1, "", "Shizuku.newProcess 返回 null")
                try {
                    withTimeout(timeoutMs) {
                        val stdout = async { process.inputStream.bufferedReader().readText() }
                        val stderr = async { process.errorStream.bufferedReader().readText() }
                        val code = process.waitFor()
                        ShellResult(code, stdout.await().trim(), stderr.await().trim())
                    }
                } finally {
                    process.destroy()
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
