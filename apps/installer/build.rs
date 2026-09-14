use std::env;
use std::path::PathBuf;

fn main() {
    let icon = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("Cargo manifest directory"))
        .join("..")
        .join("..")
        .join("assets")
        .join("branding")
        .join("harmonia-suite.ico");
    println!("cargo:rerun-if-changed={}", icon.display());

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    if !icon.is_file() {
        panic!(
            "canonical Windows application icon is missing: {}",
            icon.display()
        );
    }

    let mut resource = winresource::WindowsResource::new();
    let icon = icon.to_string_lossy();
    resource.set_icon(icon.as_ref());
    resource
        .compile()
        .expect("failed to embed canonical Windows application icon");
}
