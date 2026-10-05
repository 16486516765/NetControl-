package com.limao.netcontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limao.netcontrol.NetControlApp
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.data.NetworkRule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 受限制应用行（规则页）。 */
data class RestrictedRow(
    val info: AppInfo?,
    val rule: NetworkRule
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val netApp = app as NetControlApp
    private val ruleRepository get() = netApp.ruleRepository
    private val appRepository get() = netApp.appRepository

    val rows: StateFlow<List<RestrictedRow>> =
        ruleRepository.rulesFlow
            .combine(
                // 应用信息按需查：规则数通常很少
                kotlinx.coroutines.flow.flow {
                    emit(appRepository.loadApps().associateBy { it.packageName })
                }
            ) { rules, appMap ->
                rules.values
                    .filter { it.hasAnyRestriction }
                    .sortedBy { it.packageName }
                    .map { RestrictedRow(appMap[it.packageName], it) }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
