package com.limao.netcontrol.platform.network

/** Root 模式的网络控制器：通过 su 执行 iptables。 */
class RootNetworkController : IptablesController(RootShellExecutor()) {
    override val logTag: String = "RootNetCtrl"
    override val controllerName: String = "Root"
}

/** Shizuku 模式的网络控制器：通过 Shizuku.newProcess 执行 iptables。 */
class ShizukuNetworkController : IptablesController(ShizukuShellExecutor()) {
    override val logTag: String = "ShizukuNetCtrl"
    override val controllerName: String = "Shizuku"
}

/**
 * 无特权模式的网络控制器：所有写操作直接失败，
 * 读操作返回 UNKNOWN。绝不伪造成功。
 */
class UnsupportedNetworkController : NetworkController {
    override val controllerName: String = "Unsupported"

    private fun noPriv(): Result<Unit> =
        Result.failure(IllegalStateException("未获得系统权限（Root / Shizuku 均不可用）"))

    override suspend fun blockApp(uid: Int): Result<Unit> = noPriv()
    override suspend fun unblockApp(uid: Int): Result<Unit> = noPriv()
    override suspend fun isAppBlocked(uid: Int): Boolean = false
    override suspend fun getStatus(uid: Int): NetworkStatus = NetworkStatus.UNKNOWN
    override suspend fun detectTransports(): Result<TransportInfo> =
        Result.failure(IllegalStateException("未获得系统权限"))
    override suspend fun setWifiBlocked(uid: Int, blocked: Boolean): Result<Unit> = noPriv()
    override suspend fun setMobileBlocked(uid: Int, blocked: Boolean): Result<Unit> = noPriv()
    override suspend fun isWifiBlocked(uid: Int): Boolean = false
    override suspend fun isMobileBlocked(uid: Int): Boolean = false
}
