# ShiftBalance Windows 桌面版

独立 Tauri 窗口复用 React 页面和 Spring Boot 业务逻辑，随安装包提供 Java 25、PostgreSQL、Windows 排班求解器与 WebView2 离线安装程序。目标平台为 Windows 10/11 x64；安装后无需 Docker、Java、数据库安装或连接服务器。

桌面功能在 `feat/windows-desktop` 分支开发。服务器仍使用根目录的 Docker Compose，未设置 `SHIFTBALANCE_DESKTOP_ROOT` 时，后端沿用原启动流程；普通前端构建保留接口设置。

## 使用

1. 安装 `.exe` 后启动 ShiftBalance。
2. 首次启动选择数据根目录，设置管理员账号和至少 8 位密码。
3. 程序初始化本机数据库后显示登录页面。以后启动直接进入现有系统，账号不会重建。
4. 通过窗口顶部“数据”菜单备份、恢复、打开目录或退出。关闭窗口会等待服务与数据库安全退出，浏览器与服务器均不参与。

默认数据根目录由 Tauri `appLocalDataDir` 提供，在 Windows 上是 `%LOCALAPPDATA%\xin.onecloud.shiftbalance`。也可在首次启动选择 `D:\ShiftBalance` 等本地目录。所有用户数据与安装文件分开：

```text
数据根目录/
├─ data/
│  ├─ postgres/              PostgreSQL 数据文件，不能在运行时直接拷贝
│  └─ desktop.lock           数据目录独占锁
├─ config/
│  ├─ database.properties    随机数据库凭据与本机页面端口，不能删除
│  └─ initialized            首次启动完成标记
├─ backups/                  .dump 逻辑备份
├─ exports/                  自动导出的 PNG 排班表
└─ logs/                     桌面、后端、数据库日志
```

程序用 Tauri 的应用配置目录下 `desktop.json` 记住数据根目录。正常升级和卸载保留用户数据。更换电脑请使用备份恢复，不能只复制数据库文件；更换本机数据根目录时，先退出程序，将整个根目录迁移到新位置，再修改 `desktop.json` 中的 `root` 路径。不要把数据根目录放到安装目录、共享网络盘、同步盘，或交给其他用户共同打开。

首次安装在缺少 VC++ 运行组件的电脑上可能请求系统权限以安装随包提供的 Microsoft 运行组件；此步骤不下载文件。无管理员权限时需由单位 IT 提前安装运行组件。未签名的测试安装包可能出现 Windows 的未知发布者提示，正式分发应使用代码签名证书。

## 备份与恢复

“备份数据”将当前人员、资格、组别、账号、头像、规则和排班历史保存为 `backups/*.dump`。图片文件不包含在数据库备份中，按需单独复制 `exports`。备份可能包含个人信息，请自行将备份复制到其他磁盘保存；仅保存在同一硬盘无法防止磁盘故障。

“恢复 / 导入数据”选择可信的 `.dump` 文件并确认。程序先创建恢复前备份，再停止本机服务，将文件导入临时数据库，检查版本和账号后替换当前数据库。导入失败保留原数据库；成功后重新打开登录页面，使用备份中的账号密码。恢复会替换全部业务数据，不做合并。

从现有 Web 服务器迁移时，源数据库先升级至 v15，再用与桌面内置数据库相同主版本的 PostgreSQL 工具导出：

```sh
pg_dump -h <数据库地址> -U <数据库用户> -d shiftbalance \
  --format=custom --no-owner --no-acl --file=shiftbalance.dump
```

导出工具主版本不能低于源服务器。建议桌面内置 PostgreSQL 与服务器保持相同主版本；跨主版本导入需先验证兼容性。将文件复制到同事电脑，在桌面版初始化完成后恢复即可。分发安装包不包含现有服务器数据、密码或默认管理员账号。

桌面版有自己的升级记录表 `desktop_schema_migration`，逐个执行现有 SQL，并在同一事务内记录文件校验值。升级有数据的库前自动备份；脚本改变、数据库版本比程序新或 PostgreSQL 主版本不同会停止启动。不要以新主版本的 PostgreSQL 二进制直接打开旧数据目录；使用原版本导出备份后迁移。

## 在 Windows 构建

构建机需要 Windows x64、PowerShell 7、Node 24、JDK 25、Maven、Rust stable 和 Visual Studio C++ Build Tools（Tauri Windows 构建依赖）。这些仅供开发者使用，同事电脑不需要安装。

从 [PostgreSQL 官方 Windows 页面](https://www.postgresql.org/download/windows/) 链接到 EDB 的 binary ZIP 下载 Windows x64 PostgreSQL 二进制包。固定一个主版本用于后续升级，审查来源并记录 SHA256：

```powershell
Get-FileHash C:\Downloads\postgresql-windows-x64-binaries.zip -Algorithm SHA256
.\desktop\build-windows.ps1 `
  -PostgresZip C:\Downloads\postgresql-windows-x64-binaries.zip `
  -PostgresSha256 <已确认的64位SHA256>
```

脚本校验 ZIP，构建桌面专用前端和后端，加入 Windows OR-Tools、本地数据库与精简 Java 运行时，下载并验证 Microsoft VC++ 组件签名，执行隔离数据库回归，再打包 NSIS 安装程序。Tauri 将 WebView2 离线安装器嵌入最终包；构建时需要网络，安装运行不需要网络。Java 与 PostgreSQL 保留随包许可证。安装包体积包含这些运行环境，不是仅几 MB 的窗口壳。

安装包位置：

```text
desktop/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/*.exe
```

本地先准备资源再调试：

```powershell
.\desktop\build-windows.ps1 -PostgresZip <ZIP> -PostgresSha256 <SHA256> -PrepareOnly
cd desktop
npm ci
npm run dev
```

推送到 `feat/windows-desktop` 时会自动使用 `desktop/runtime-lock.json` 中固定的 PostgreSQL 下载地址和 SHA256 构建。也可在 GitHub Actions 手动运行 **Windows desktop installer**，选择此开发分支，可选覆盖官方 EDB ZIP 地址和校验值，完成后下载 `ShiftBalance-Windows-x64` artifact。工作流只上传构建产物，不自动发布 Release 或部署 Web 服务。

## 检查

```sh
sh backend/scripts/check-desktop.sh
# 完整本地数据库生命周期检查：只创建 backend/target 下的隔离测试数据
sh backend/scripts/check-desktop.sh --postgres-bin /path/to/postgresql/bin
cd desktop && npm ci && npm run check
cd ../frontend && npm run build
```

Windows 构建脚本会执行真正的初始化、登录、备份、恢复、重启和退出测试，并检查 Windows 原生求解器。发布前仍需在 Windows 实机离线验收：首次安装、重复启动聚焦、中文与空格路径、生成和调整排班、拖拽、上传头像、发布导出 PNG、备份恢复、升级保留数据，以及关闭窗口后无残留服务。macOS 上的编译与数据库测试不能替代 Windows 验收。
