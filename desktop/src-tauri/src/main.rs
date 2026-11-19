#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    fs,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
};
use tauri::{
    menu::{Menu, MenuItem, Submenu},
    webview::DownloadEvent,
    Emitter, Manager, WebviewUrl, WebviewWindowBuilder,
};

#[derive(Default)]
struct Services {
    child: Mutex<Option<Child>>,
    root: Mutex<Option<PathBuf>>,
    origin: Mutex<Option<String>>,
    quitting: AtomicBool,
}

#[derive(Serialize, Deserialize)]
struct LocalSettings {
    root: PathBuf,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StartupInfo {
    root: String,
    initialized: bool,
}

fn configuration(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    Ok(app
        .path()
        .app_config_dir()
        .map_err(|e| e.to_string())?
        .join("desktop.json"))
}

fn configured_root(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let path = configuration(app)?;
    if path.exists() {
        let settings: LocalSettings =
            serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?).map_err(|_| {
                "桌面配置损坏，请检查 desktop.json，程序不会自动切换到空数据库。".to_string()
            })?;
        if !settings.root.is_dir() {
            return Err(
                "已配置的数据目录不存在，请连接原磁盘或恢复数据目录；程序不会自动创建空数据库。"
                    .into(),
            );
        }
        return Ok(settings.root);
    }
    app.path().app_local_data_dir().map_err(|e| e.to_string())
}

fn bundled_page(window: &tauri::WebviewWindow) -> Result<(), String> {
    let url = window.url().map_err(|e| e.to_string())?;
    if url.scheme() == "tauri"
        || (url.scheme() == "http" && url.host_str() == Some("tauri.localhost"))
    {
        Ok(())
    } else {
        Err("只有程序自带的启动页可以操作本机服务。".into())
    }
}

#[tauri::command]
fn startup_info(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
) -> Result<StartupInfo, String> {
    bundled_page(&window)?;
    let root = configured_root(&app)?;
    Ok(StartupInfo {
        initialized: root.join("config/initialized").is_file(),
        root: root.to_string_lossy().into(),
    })
}

#[tauri::command]
async fn choose_directory(window: tauri::WebviewWindow) -> Result<Option<String>, String> {
    bundled_page(&window)?;
    tauri::async_runtime::spawn_blocking(|| {
        rfd::FileDialog::new()
            .set_title("选择排班数据根目录")
            .pick_folder()
            .map(|path| path.to_string_lossy().into_owned())
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn start_services(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
    root: String,
    username: String,
    password: String,
) -> Result<(), String> {
    bundled_page(&window)?;
    let state = app.state::<Services>();
    let mut child = state.child.lock().unwrap();
    if child.is_some() {
        return Err("排班服务已经启动。".into());
    }
    if cfg!(windows) && root.starts_with("\\\\") {
        return Err("请选择本机磁盘目录，不支持网络共享目录。".into());
    }
    let root = PathBuf::from(root);
    if !root.is_absolute() {
        return Err("请选择完整的本地目录路径。".into());
    }
    fs::create_dir_all(&root).map_err(|e| format!("数据目录不可写：{e}"))?;
    let root = root.canonicalize().map_err(|e| e.to_string())?;
    // PostgreSQL tools expect a normal drive path, not Rust's Windows verbatim prefix.
    #[cfg(windows)]
    let root = PathBuf::from(root.to_string_lossy().trim_start_matches(r"\\?\"));
    let initialized = root.join("config/initialized").is_file();
    if !initialized
        && (username.trim().is_empty()
            || username.chars().count() > 100
            || password.len() < 8
            || password.len() > 72)
    {
        return Err("首次使用请设置管理员账号，以及 8–72 字节的密码。".into());
    }
    let payload = app
        .path()
        .resource_dir()
        .map_err(|e| e.to_string())?
        .join("payload");
    let java = payload.join(if cfg!(windows) {
        "runtime/bin/java.exe"
    } else {
        "runtime/bin/java"
    });
    if !java.is_file() || !payload.join("backend.jar").is_file() {
        return Err("缺少内置运行环境，请先执行桌面资源构建脚本。".into());
    }
    fs::create_dir_all(root.join("logs")).map_err(|e| e.to_string())?;
    let error_log = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(root.join("logs/desktop.log"))
        .map_err(|e| e.to_string())?;
    let locator = configuration(&app)?;
    fs::create_dir_all(locator.parent().unwrap()).map_err(|e| e.to_string())?;
    fs::write(
        locator,
        serde_json::to_vec_pretty(&LocalSettings { root: root.clone() }).unwrap(),
    )
    .map_err(|e| e.to_string())?;
    let mut command = Command::new(java);
    command
        .args([
            "-Dfile.encoding=UTF-8",
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
        ])
        .arg(payload.join("backend.jar"))
        .current_dir(&root)
        .env("SHIFTBALANCE_DESKTOP_ROOT", &root)
        .env("SHIFTBALANCE_DESKTOP_PAYLOAD", &payload)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(error_log);
    // Prevent inherited JVM arguments from changing the isolated local backend.
    for variable in ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"] {
        command.env_remove(variable);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW; the Tauri window owns the lifecycle.
    }
    let mut process = command
        .spawn()
        .map_err(|e| format!("无法启动内置 Java：{e}"))?;
    let credentials = if initialized {
        json!({"username":"", "password":""})
    } else {
        json!({"username": username.trim(), "password": password})
    };
    if let Err(error) = writeln!(process.stdin.as_mut().unwrap(), "{credentials}") {
        let _ = process.kill();
        let _ = process.wait();
        return Err(error.to_string());
    }
    let stdout = process.stdout.take().unwrap();
    *state.root.lock().unwrap() = Some(root.clone());
    *child = Some(process);
    drop(child);
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Some(payload) = line.strip_prefix("SHIFTBALANCE_DESKTOP ") {
                if let Ok(event) = serde_json::from_str::<serde_json::Value>(payload) {
                    let message = event["message"].as_str().unwrap_or("操作失败");
                    match event["type"].as_str() {
                        Some("ready") => {
                            if let Ok(url) = message.parse::<tauri::Url>() {
                                if url.scheme() == "http"
                                    && url.host_str() == Some("127.0.0.1")
                                    && url.port().is_some()
                                {
                                    *app.state::<Services>().origin.lock().unwrap() =
                                        Some(url.origin().ascii_serialization());
                                    if let Some(window) = app.get_webview_window("main") {
                                        if let Err(error) = window.navigate(url) {
                                            notice("无法显示排班页面", &error.to_string(), true);
                                        }
                                    }
                                }
                            }
                        }
                        Some("backup") => notice(
                            "备份完成",
                            &format!("备份已保存：\n{message}\n\n可通过“数据”菜单打开备份目录。"),
                            false,
                        ),
                        Some("error") => notice("操作未完成", message, true),
                        _ => {
                            let _ = app.emit("desktop-status", &event);
                        }
                    }
                }
            }
        }
        let state = app.state::<Services>();
        if let Some(mut process) = state.child.lock().unwrap().take() {
            let _ = process.wait();
        }
        if state.quitting.load(Ordering::SeqCst) {
            app.exit(0);
        } else {
            notice("排班服务已停止", "请查看数据目录下 logs/desktop.log 和 logs/backend.log。关闭程序后可以重新启动，现有数据会保留。", true);
            app.exit(1);
        }
    });
    Ok(())
}

fn notice(title: &str, message: &str, error: bool) {
    rfd::MessageDialog::new()
        .set_title(title)
        .set_description(message)
        .set_level(if error {
            rfd::MessageLevel::Error
        } else {
            rfd::MessageLevel::Info
        })
        .show();
}

fn send(app: &tauri::AppHandle, message: serde_json::Value) -> Result<(), String> {
    let state = app.state::<Services>();
    let mut child = state.child.lock().unwrap();
    let stdin = child
        .as_mut()
        .and_then(|process| process.stdin.as_mut())
        .ok_or("排班服务尚未启动。")?;
    writeln!(stdin, "{message}").map_err(|e| e.to_string())
}

fn shutdown(app: &tauri::AppHandle) {
    let state = app.state::<Services>();
    if state.quitting.swap(true, Ordering::SeqCst) {
        return;
    }
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_title("ShiftBalance · 正在停止本地服务，请稍候…");
    }
    let mut child = state.child.lock().unwrap();
    if let Some(process) = child.as_mut() {
        // Closing the private pipe also handles parent crashes; Java stops HTTP before PostgreSQL.
        process.stdin.take();
    } else {
        drop(child);
        app.exit(0);
    }
}

fn open_directory(path: &Path) -> Result<(), String> {
    let mut command = if cfg!(windows) {
        Command::new("explorer.exe")
    } else if cfg!(target_os = "macos") {
        Command::new("open")
    } else {
        Command::new("xdg-open")
    };
    command
        .arg(path)
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn main() {
    tauri::Builder::default()
        .manage(Services::default())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") { let _ = window.unminimize(); let _ = window.set_focus(); }
        }))
        .invoke_handler(tauri::generate_handler![startup_info, choose_directory, start_services])
        .setup(|app| {
            let data_menu = Submenu::with_items(app, "数据", true, &[
                &MenuItem::with_id(app, "backup", "备份数据", true, None::<&str>)?,
                &MenuItem::with_id(app, "restore", "恢复 / 导入数据…", true, None::<&str>)?,
                &MenuItem::with_id(app, "data", "打开数据目录", true, None::<&str>)?,
                &MenuItem::with_id(app, "exports", "打开排班图片目录", true, None::<&str>)?,
                &MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?,
            ])?;
            let menu = Menu::with_items(app, &[&data_menu])?;
            app.set_menu(menu)?;
            WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("ShiftBalance · 排班系统").inner_size(1440.0, 960.0).min_inner_size(1000.0, 700.0)
                // Native file-drop interception would swallow the schedule's HTML drag-and-drop.
                .disable_drag_drop_handler()
                .on_navigation({
                    let handle = app.handle().clone();
                    move |url| {
                        url.scheme() == "tauri" || url.host_str() == Some("tauri.localhost") ||
                            handle.state::<Services>().origin.lock().unwrap().as_deref() == Some(url.origin().ascii_serialization().as_str())
                    }
                })
                .on_download(|webview, event| {
                    if let DownloadEvent::Requested { destination, .. } = event {
                        let handle = webview.app_handle();
                        let state = handle.state::<Services>();
                        let root = state.root.lock().unwrap();
                        let Some(root) = root.as_ref() else { return false; };
                        let Some(name) = destination.file_name() else { return false; };
                        if !name.to_string_lossy().to_ascii_lowercase().ends_with(".png") { return false; }
                        let mut output = root.join("exports").join(name);
                        if output.exists() {
                            let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis();
                            output = root.join("exports").join(format!("{stamp}-{}", name.to_string_lossy()));
                        }
                        *destination = output;
                    }
                    true
                }).build()?;
            Ok(())
        })
        .on_menu_event(|app, event| {
            let app = app.clone(); let id = event.id().as_ref().to_string();
            std::thread::spawn(move || {
                let result = match id.as_str() {
                    "quit" => { shutdown(&app); Ok(()) }
                    "backup" => send(&app, json!({"command":"backup"})),
                    "restore" => {
                        if let Some(path) = rfd::FileDialog::new().set_title("选择排班数据库备份").add_filter("数据库备份", &["dump"]).pick_file() {
                            let confirmed = rfd::MessageDialog::new().set_title("恢复排班数据")
                                .set_description("恢复会替换当前人员、账号、规则和排班历史。程序会先备份当前数据；请先保存正在编辑的排班。是否继续？")
                                .set_level(rfd::MessageLevel::Warning).set_buttons(rfd::MessageButtons::YesNo).show();
                            if confirmed == rfd::MessageDialogResult::Yes { send(&app, json!({"command":"restore", "path":path})) } else { Ok(()) }
                        } else { Ok(()) }
                    }
                    "data" | "exports" => {
                        let root = app.state::<Services>().root.lock().unwrap().clone();
                        match root {
                            Some(root) => open_directory(&if id == "exports" { root.join("exports") } else { root }),
                            None => Err("请先启动排班服务。".into()),
                        }
                    }
                    _ => Ok(()),
                };
                if let Err(error) = result { notice("操作未完成", &error, true); }
            });
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close(); shutdown(window.app_handle());
            }
        })
        .build(tauri::generate_context!()).expect("无法创建桌面程序")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                if app.state::<Services>().child.lock().unwrap().is_some() { api.prevent_exit(); shutdown(app); }
            }
        });
}

#[cfg(test)]
mod tests {
    #[test]
    fn offline_bundle_and_local_only_permissions() {
        let config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        assert_eq!(
            config["bundle"]["windows"]["webviewInstallMode"]["type"],
            "offlineInstaller"
        );
        let permissions: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/setup.json")).unwrap();
        assert!(permissions.get("remote").is_none());
        assert_eq!(permissions["local"], true);
    }
}
