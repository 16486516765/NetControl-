# 联网控制器（NetControl）

一个真正可用的 Android 应用联网控制器：按应用控制是否允许联网，
支持 Root 与 Shizuku 两种特权模式，UI 基于
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
（Backdrop 2.0.1）构建 Liquid Glass 风格。

## 功能

- 已安装应用列表（图标 / 名称 / 包名 / UID / 实际联网状态）
- 应用搜索（名称、包名）、排序（名称 / 包名 / 状态）、过滤（系统应用 / 仅被限制）
- 单个应用联网开关：真正通过 iptables 按 UID 写入系统规则，并校验执行结果
- Wi-Fi / 移动数据分组控制：仅当设备可可靠识别两类接口时启用，
  否则如实显示「当前控制模式无法可靠区分 Wi-Fi 与移动数据」
- 规则持久化（DataStore）；启动时对比系统实际规则，丢失则重新应用
- 开机自动恢复规则（可在设置中开关）
- 应用卸载自动清理规则；应用更新（UID 变化）自动重新应用
- 特权优先级：Root > Shizuku > 无特权；无特权时绝不伪造“控制成功”

## 网络控制原理

在 `OUTPUT` 链按 UID 匹配插入 REJECT 规则：

```
iptables -I OUTPUT -m owner --uid-owner <uid> -j REJECT
```

- 每条规则写入 / 删除后都用 `iptables -C` 校验，不假设成功
- IPv4（iptables）为必需路径；IPv6（ip6tables）为尽力路径
- 列表页一次 su 调用批量读取全部 UID 规则，不卡顿

## 开机恢复的诚实说明

- 部分设备后台限制严格，可能导致开机广播受限
- Shizuku 在开机时通常尚未运行，此时无法恢复
- 以上情况 App 会标记「待恢复」，下次启动时提示手动恢复，
  绝不伪造“恢复成功”

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/`

## 版本

- applicationId：`com.limao.netcontrol`
- versionName：`1.0.0`（versionCode 1）
- minSdk 26（Android 8.0）/ targetSdk 34 / compileSdk 34
- Kotlin 2.0.21 / AGP 8.5.2 / Gradle 8.7
- Compose（BOM 2026.08.00，compose-ui 1.12.0）/ Material3 1.4.0
- Backdrop（AndroidLiquidGlass）2.0.1 / shapes 1.2.1
- Shizuku api/provider 13.1.5
- DataStore Preferences 1.1.7

## 隐私

纯本地工具：无广告、无统计、无账号、无服务器，不上传应用列表、
网络规则、Root / Shizuku 状态等任何数据。

## 已知限制

- 真机上的 Root / Shizuku 网络控制行为需在真实设备验证
- Wi-Fi / 移动数据分组控制依赖设备接口命名（wlan*/rmnet* 等），
  无法识别时自动降级为统一控制并如实提示
