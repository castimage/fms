//! 程序二进制入口，装配与启动逻辑在库crate(`fms_rgw::run`)，本文件仅为二进制壳
#![forbid(unsafe_code)]

fn main() -> Result<(), Box<dyn std::error::Error>> {
    fms_rgw::run()
}
