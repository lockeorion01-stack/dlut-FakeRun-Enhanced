# dlut-FakeRun

Android 模拟定位工具，按 KML 路线及指定配速更新系统测试位置。

## 使用方式

1. 启用开发者选项
2. 在「选择模拟位置信息应用」中选择该应用
3. 打开 App，授予定位权限
4. 选择路线并配置配速，点击「开始」

模拟器用户也可以使用下方的 [ADB 授权方式](#模拟器通过-adb-授权)，代替步骤 1、2 中的手动设置。
普通定位权限与模拟位置授权是两项独立权限，步骤 3 仍需完成。

## 功能

- **路线管理**：12 条内置闭环路线，另支持从文件导入 `.kml`（可新增 / 替换 / 删除）
- **配速控制**：使用 `分:秒`（例如 `5:33`）输入每公里配速，或用 ±5 秒精确微调；兼容旧的小数分钟输入（`5.55` = `5:33`）。运行前设置配速，运行期间锁定参数
- **重复圈数**：跑够设定圈数后自动结束
- **实时状态**：当前配速、速度、进度条、圈数、已用时间、剩余距离
- **更新间隔**：可调（0.1 ~ 5 秒，默认 0.3 秒）
- **后台运行**：前台服务独立维护运行状态，旋转屏幕、返回桌面或重新打开页面不会重启任务；通知栏可暂停、恢复和停止
- **状态与错误**：区分待命、启动中、运行中、暂停、完成、停止和失败；每次定位写入前后检查授权，同时监听模拟位置 AppOp 变化。运行中或暂停时撤销授权会结束任务、停止累计进度，并保存失败原因；重新授权后需要手动开始新任务
- **配置记忆**：保存上次选择的路线、配速、圈数和更新间隔；内置路线使用稳定资源名称标识，支持将 1.0 版的数字资源 ID 迁移到新标识，避免升级后选错路线
- **运行结果**：本地保存最近一次完成、停止或失败的路线名称、里程、用时与错误原因；后台结束或重新打开应用后仍可查看
- **目标预览**：参数区独立显示下次运行的圈数和目标距离；手动输入和加减按钮都会立即更新预览，不改动上次运行结果
- **可读性**：提高辅助文字和停止按钮的对比度；输入框与按钮按文字自适应高度，控制按钮分两行显示，小屏幕或大字体时运行数据改为纵向排列；适配系统栏、刘海和软键盘边距

计时使用单调时钟，正常的 5 秒更新会累计完整的 5 秒距离。若回调停顿超过
`max(2 秒, 更新间隔 × 2)`，仅计入一个更新周期，并同步扣除停顿期间的计时，避免位置跳跃。
运行时持有部分唤醒锁，暂停、完成、停止或失败时释放。退出页面不会停止正在运行的任务，
请使用页面或通知栏的停止按钮。系统终止进程后不会自动续跑。
“最近一次运行”只保存已经结束的任务摘要，不包含用于恢复运行的完整路线；
进程被系统直接终止时，尚未结束的任务不保证留下最终摘要。

## 环境支持

- Android 8.0（API 26）及以上
- 当前编译和目标 SDK：Android 16（API 36）
- CI 配置 Android 8.0 / 14 / 16（API 26 / 34 / 36）模拟器测试，实际通过情况以对应提交的 Actions 报告为准
- 真机、厂商后台策略以及长时间锁屏运行需在目标设备上验证，操作步骤见 [设备验证说明](docs/testing.md)
- 本项目的自动测试验证自身运行状态、系统测试位置接口和结果保存，不证明 Keep、小米运动健康、华为运动健康等第三方应用兼容；是否接收模拟位置取决于其版本与运行环境

## 模拟器通过 ADB 授权

更换模拟器或新建实例后，需要连接该实例的 ADB，并单独授予 FakeGPS 模拟位置权限。
授权通常会保留，不需要每次启动都执行；重装应用或重建模拟器后可能需要重新授权。

### 1. 开启 ADB 并连接实际端口

先在模拟器设置中开启 **ADB 调试**，查看当前实例的 ADB 端口，并确认已安装 FakeGPS。
不同模拟器、不同实例的端口可能不同，不要固定使用 `emulator-5554` 或 `5556`。

在 Windows PowerShell 中执行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$port = Read-Host "请输入该模拟器的 ADB 端口（只填数字，例如 5556）"
$device = "127.0.0.1:$port"

& $adb connect $device
& $adb devices -l
```

`$adb` 使用 Android SDK 的默认安装路径。如果找不到文件，请安装 Android SDK Platform-Tools，
或将 `$adb` 改为本机实际的 `adb.exe` 路径。

端口输入框中**只填写数字**，例如 `5556`，不要填写 `127.0.0.1:5556`。
脚本会自动添加 `127.0.0.1:`；填写完整地址会重复拼接，产生 `no host in` 或
`unknown host service` 错误。如果已经填错，请重新执行上面的连接步骤并只输入实际端口号。

确认列表中与 `$device` 对应的设备状态为 **`device`**，再继续授权。
例如 `127.0.0.1:5556 device` 表示该地址已连接；`offline` 表示尚未建立可用连接。

### 2. 授权并验证

在同一个 PowerShell 窗口中执行：

```powershell
& $adb -s $device shell appops set com.langqi.fakegps android:mock_location allow
& $adb -s $device shell appops get com.langqi.fakegps android:mock_location
```

看到 **`MOCK_LOCATION: allow`** 表示授权成功。返回 FakeGPS，授予普通定位权限，
选择路线并点击「开始」。ADB 授权可以省去开发者选项中的手动选择，应用仍需要系统授予模拟位置权限。

### 出现 `device offline` 时

`offline` 表示命令还没有进入模拟器，反复执行授权命令不会生效。先检查：

- 模拟器是否已启动完成，ADB 调试是否开启。
- 当前实例的实际端口是否与 `$device` 一致；重启或切换实例后应重新确认。
- `adb devices -l` 中可能保留旧的离线连接，应使用当前实例对应且状态为 `device` 的地址。

确认端口后，在同一个 PowerShell 窗口中重新连接：

```powershell
& $adb disconnect $device
& $adb connect $device
& $adb devices -l
```

如果仍为 `offline`，重启对应模拟器实例，重新确认端口后再连接。
恢复为 `device` 后，再执行授权与验证命令。

## 自定义路线

导入的 KML 需要满足：

- 含单个 `<LineString>` 元素（工具导出的文件常同时混有多个 `<Point>` 地标，
  解析时会优先取 LineString，不会取错）
- 支持带命名空间前缀的 KML；含多条 LineString 时提示分别导出，避免静默丢失路径
- 坐标格式为 `经度,纬度[,高程]`，高程可选
- 至少 2 个坐标点
- 路线长度大于零，坐标和高程必须是有限数值
- 文件最多 5 MB、50000 个坐标点；不接受 DTD 或实体声明
- 建议首尾坐标一致以形成闭环；不闭环时每圈会从终点直接跳回起点

导入的文件保存在应用私有目录 `filesDir/routes/` 下。文件读取、解析和累计距离索引在后台线程完成，
开始运行时直接复用已校验的路线，每次定位通过二分查找确定路段。
新文件先保存为不会出现在路线列表的 `.tmp` 文件，通过校验后原子替换正式文件；
校验、复制或提交失败会保留原路线。

经度跨越 ±180° 时按短方向插值。接近地球对跖点、Vincenty 算法不收敛时，
距离计算退回平均地球半径的球面估算（与 WGS-84 椭球距离的误差约在 0.6% 以内）。

## 关于内置路线的坐标

内置路线的坐标位于广西南宁一带，与「dlut」（大连理工）并不在同一地点。
如果你需要大连的路径，请自行用 KML 导出工具制作后导入。

## 构建

```bash
./gradlew assembleDebug
```

要求 JDK 17、Android SDK Platform 36 与 Build Tools 36.0.0（SDK 路径写在 `local.properties`）。
项目使用 Gradle 8.13 和 Android Gradle Plugin 8.13.2，Wrapper 配置了官方分发包 SHA-256 校验。
Windows PowerShell 下将 `./gradlew` 换成 `.\gradlew.bat`。

跑单元测试：

```bash
./gradlew testDebugUnitTest
```

构建、静态检查和设备测试：

```bash
./gradlew assembleDebug lintDebug
./gradlew connectedDebugAndroidTest
```

单元测试覆盖几何计算、所有内置路线、KML 校验、导入与替换失败、配速换算、
各更新间隔、暂停恢复、定位失败和完成状态，以及经度跨界、近对跖点、重复坐标和 50000 点路线查找。
设备测试还覆盖界面重建、重新打开、后台运行、模拟位置授权失败、Android 文件系统上的原子替换，
以及完全解绑后完成、失败、通知栏停止的结果保存和圈数编辑预览。
新增回归覆盖运行中撤销授权、暂停中撤权、授权监听回调到达前恢复运行、后台撤权后的结果保存、
旧版路线标识迁移及重新打开，以及 320 / 375 / 640 dp 布局在 1 倍 / 2 倍字体下的文本与触控尺寸。
旧版 ID 迁移使用固定的 1.0 版资源表，不按新版本的数字 ID 猜测路线；未知旧构建的 ID 回退到默认选择。
设备测试需要可正常安装 APK、执行 instrumentation 的 Android 设备或模拟器，
会临时设置本应用的模拟位置 AppOp，并在测试结束后恢复原值。

## 自动检查与构建产物

GitHub Actions 会在推送、Pull Request 和手动触发时执行单元测试、Lint 和 APK 构建，
并分别使用 API 26、34、36 模拟器执行设备测试。各系统版本单独上传测试报告，一个版本失败不会取消其他版本。
运行报告与 Debug APK 可从对应 Actions 运行的 Artifacts 下载。
工作流需要推送到 GitHub 后才会在那里执行。

APK 属于构建产物，不再跟踪在源码仓库中。也可以本地运行 `./gradlew assembleDebug`，
输出位于 `app/build/outputs/apk/debug/app-debug.apk`。
Debug APK 仅用于开发测试，不同电脑或 CI 运行可能使用不同的 Debug 证书，不能保证相互覆盖安装。
需要持续升级时，请使用同一固定签名生成的 Release APK。

## 正式签名与发布

版本由根目录的 `version.properties` 统一管理。每次正式发布递增 `versionCode` 并更新
`versionName`（例如 `1.1.0` → `1.1.1`）；发布工作流会检查标签与版本名称一致，并拒绝已有标签的版本号回退。

仓库使用以下 GitHub Actions Secrets，不将私钥或密码提交到源码：

| Secret | 用途 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 固定 Release 密钥库文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

当前固定 Release 证书的 SHA-256 指纹（公开信息）：

```text
1AD0185E47003E72567AA0A335B741466460417B3AA2190667876E6AD472375D
```

首次签名材料已配置到本仓库 Secrets。本机备份位于
`%USERPROFILE%\.android\signing\dlut-fakerun\`，其中 `release.p12` 是密钥库，
`signing-secrets.json` 包含本地构建所需路径和密码；该目录限制了访问权限。
请另行妥善备份，后续版本持续使用同一密钥。Fork 本仓库时需要配置自己的 Secrets 与证书指纹。

发布方式：

1. 更新 `version.properties`，提交并推送代码。
2. 仅需要签名安装包时，在 Actions 中手动运行 **Android release**，从 Artifacts 下载。
3. 正式发布时，创建与版本一致的标签，例如 `git tag v1.1.0`，再执行 `git push origin v1.1.0`。
4. 发布工作流先运行完整检查和三个版本的设备测试，然后签名、验证 APK；标签触发的运行会创建 GitHub Release，附带 `FakeGPS-版本.apk`、`SHA256SUMS.txt` 和 `signature.txt`。

本地 Windows 签名构建：

```powershell
.\scripts\build-release.ps1 -SigningConfig "$env:USERPROFILE\.android\signing\dlut-fakerun\signing-secrets.json"
```

也可以自行提供 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD` 四个环境变量后执行 `./gradlew :app:assembleRelease`。
缺少任一项配置会明确失败，不会再静默生成无法安装的 unsigned APK。
签名产物位于 `app/build/outputs/apk/release/app-release.apk`。

可以使用 Android SDK 的 `apksigner verify --verbose --print-certs <APK路径>` 检查签名，
将输出的证书 SHA-256 与上方指纹比较。

### 从旧 Debug 安装包迁移

新 Release 签名无法覆盖安装证书不同的旧 Debug APK。这是 Android 的安装校验规则。
首次切换前，通过路线选择器确认需要保留的路线，并保留原始 KML 文件；卸载旧应用后安装 Release，
重新导入路线、设置参数并授权。卸载会清除应用私有路线和运行摘要。
之后使用同一 Release 签名和更高 `versionCode` 的版本即可覆盖升级并保留应用数据。
