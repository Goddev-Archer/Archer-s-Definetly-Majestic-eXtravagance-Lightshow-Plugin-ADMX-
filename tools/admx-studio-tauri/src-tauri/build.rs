fn main() {
    let temp_icon = std::env::temp_dir().join("admx_icon.ico");
    let local_icon = std::path::Path::new("icons/icon.ico");
    if local_icon.exists() {
        let _ = std::fs::copy(local_icon, &temp_icon);
    }
    let windows = tauri_build::WindowsAttributes::new()
        .window_icon_path(temp_icon);
    let attrs = tauri_build::Attributes::new().windows_attributes(windows);
    tauri_build::try_build(attrs).expect("failed to run tauri-build");
}
