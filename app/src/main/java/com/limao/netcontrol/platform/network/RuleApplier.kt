package com.limao.netcontrol.platform.network

import com.limao.netcontrol.data.NetworkRule

/**
 * 把 [NetworkRule] 真正应用到系统。
 *
 * 薄委托：实际逻辑在 [NetworkController.applyRule]。
 * IptablesController 提供差异化单脚本实现（只删多余、只加缺失，
 * 清理/写入/验证在同一个 Shell 脚本里完成）；
 * 其他控制器走接口默认实现（先清理再重写，与旧行为一致）。
 */
object RuleApplier {

    suspend fun apply(controller: NetworkController, rule: NetworkRule): Result<Unit> =
        controller.applyRule(rule)

    /**
     * 批量应用多条规则（开机恢复等场景），按包名逐条汇报成功/失败。
     * IptablesController 用单脚本批处理；其他控制器逐条回退。
     */
    suspend fun applyAll(
        controller: NetworkController,
        rules: List<NetworkRule>
    ): Map<String, Result<Unit>> = controller.applyRules(rules)
}
