package com.limao.netcontrol.platform.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * 稳健的进程执行：后台线程消费 stdout/stderr（防缓冲区满死锁），
 * 看门狗线程超时杀进程，主线程阻塞 waitFor。
 * 避开 Process.waitFor(timeout) + exitValue() 在某些实现（如 Shizuku）上的竞态。
 */
internal fun execProcessRobust(process: Process, timeoutMs: Long): ShellResult {
    val stdoutBuilder = StringBuilder()
    val stderrBuilder = StringBuilder()
    val outThread = Thread {
        try {
            process.inputStream.bufferedReader().forEachLine { stdoutBuilder.appendLine(it) }
        } catch (_: Exception) {}
    }
    val errThread = Thread {
        try {
            process.errorStream.bufferedReader().forEachLine { stderrBuilder.appendLine(it) }
        } catch (_: Exception) {}
    }
    outThread.isDaemon = true
    errThread.isDaemon = true
    outThread.start()
    errThread.start()

    // 看门狗：超时后强制杀进程
    val watchdog = Thread {
        try {
            Thread.sleep(timeoutMs)
            if (process.isAlive) {
                try { process.destroyForcibly() } catch (_: Exception) {}
            }
        } catch (_: InterruptedException) {
            // 正常完成，中断看门狗
        } catch (_: Exception) {}
    }
    watchdog.isDaemon = true
    watchdog.start()

    return try {
        val code = process.waitFor()
        watchdog.interrupt()
        outThread.join(2000)
        errThread.join(2000)
        // 如果是被看门狗杀的，waitFor 返回的可能是非 0，调用方按 exitCode 判断
        ShellResult(code, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
    } catch (e: InterruptedException) {
        try { process.destroyForcibly() } catch (_: Exception) {}
        ShellResult(-1, "", "interrupted: ${e.message}")
    } catch (e: Exception) {
        try { process.destroyForcibly() } catch (_: Exception) {}
        ShellResult(-1, "", e.message ?: "exception")
    } finally {
        try { process.destroy() } catch (_: Exception) {}
    }
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
                execProcessRobust(process, timeoutMs)
            } catch (e: Exception) {
                try { process?.destroyForcibly() } catch (_: Exception) {}
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
            var process: Process? = null
            try {
                if (!Shizuku.pingBinder()) {
                    return@withContext ShellResult(-1, "", "Shizuku binder 不可用")
                }
                // Shizuku 13.1.5+ 将 newProcess 私有化，用反射调用（Method 已缓存）
                process = newProcessMethod.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as? Process
                    ?: return@withContext ShellResult(-1, "", "Shizuku.newProcess 返回 null")
                execProcessRobust(process, timeoutMs)
            } catch (e: Exception) {
                try { process?.destroyForcibly() } catch (_: Exception) {}
                Log.e(TAG, "shizuku exec failed: $command", e)
                ShellResult(-1, "", e.message ?: "exception")
            }
        }

    companion object {
        private const val TAG = "ShizukuShell"
    }
}
