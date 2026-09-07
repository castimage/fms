//! 静态资源 + spa history fallback(`Router` fallback，非`/api`/ 且非`/healthz`)，
//! 路径二次校验 + canonicalize纵深防御，防 ../ 与符号链接逃逸后由 `ServeDir` 取文件
//! 304由其处理，未命中则返回 `index.html`(no-store + `ETag` 304)，`Cache-Control`分级
//! (`index.html` no-store，hash资源immutable 1y，其余3600)
//!
//! 调用关系，`static_handler` ← `main::build_router` fallback，仅外层链trace/安全头/日志
#![forbid(unsafe_code)]

use crate::filter::{decode_for_check, normalize_path};
use crate::state::AppState;
use crate::types::{GatewayError, error_response, trace_id_of};
use axum::body::Body;
use axum::extract::State;
use axum::http::header::{CACHE_CONTROL, ETAG, HeaderValue};
use axum::http::{Method, Request, Response, StatusCode};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tower::ServiceExt;
use tower_http::services::{ServeDir, ServeFile};
use tracing::warn;

/// 敏感文件扩展名/备份名后缀黑名单，静态目录严禁输出，密钥类 + 备份/源码/配置类
/// (.`sql/.bak/.swp/.log/.txt/.json/.yaml/.sh`/.ps1等)+ 编辑器残留，*.orig/*.save/*.old/*.dist，
/// hash资源(.`js/.css`/.png白名单扩展)不受影响
fn is_sensitive_extension(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    const SENSITIVE_EXTS: &[&str] = &[
        // 密钥与凭据
        ".env",
        ".pem",
        ".key",
        ".crt",
        ".p12",
        ".p8",
        ".git",
        ".npmrc",
        ".htpasswd",
        ".ds_store",
        // 数据库备份 / 配置类 / 脚本类 / 文档类
        ".sql",
        ".bak",
        ".swp",
        ".log",
        ".txt",
        ".conf",
        ".cfg",
        ".ini",
        ".json",
        ".yaml",
        ".yml",
        ".sh",
        ".bat",
        ".ps1",
        ".toml",
        ".local",
        ".example",
        ".dist",
        ".orig",
        ".save",
        ".old",
        ".php",
        ".py",
    ];
    if SENSITIVE_EXTS.iter().any(|ext| lower.ends_with(ext)) {
        return true;
    }
    // 编辑器备份残留，config.php~、index.html~ 等
    lower.ends_with('~')
}

/// 处理函数(`Router` fallback，非`/api`/ 且非`/healthz`)
pub async fn static_handler(
    State(state): State<Arc<AppState>>,
    req: Request<Body>,
) -> Response<Body> {
    let trace_id = trace_id_of(&req);

    // 二次校验，fallback未经安全链，内嵌filter校验，防 ../ 与%2e%2e编码绕过
    let path = req.uri().path().to_string();
    let normalized = match normalize_path(&path) {
        Ok(p) => p,
        Err(_) => {
            return error_response(GatewayError::BadRequest("invalid path".to_string()), &trace_id);
        }
    };

    // /api/ 前缀永不落到静态，路由保证之外的第二道防线，percent-decode + 大小写不敏感，
    // 拦截 /api/vouchers与/api%2Fvouchers变体
    if decode_for_check(&normalized)
        .to_ascii_lowercase()
        .starts_with("/api/")
    {
        return error_response(GatewayError::NotFound(normalized), &trace_id);
    }

    // 拒绝dotfile与敏感文件，路径任一段以 . 开头，.well-known除外或命中敏感扩展名则 404
    if normalized
        .split('/')
        .any(|seg| !seg.is_empty() && seg.starts_with('.') && seg != ".well-known")
        || is_sensitive_extension(&normalized)
    {
        return error_response(GatewayError::NotFound(normalized), &trace_id);
    }

    // 静态目录，配置[static] dir，默认 ./static
    let static_dir = PathBuf::from(state.cfg.static_cfg.dir.clone());
    let static_canon = match tokio::fs::canonicalize(&static_dir).await {
        Ok(c) => c,
        Err(e) => {
            warn!(path = %static_dir.display(), error = %e, "静态目录不可用");
            return error_response(
                GatewayError::Internal("static dir unavailable".to_string()),
                &trace_id,
            );
        }
    };

    // 候选文件canonicalize，存在且位于static_dir内才允许输出，纵深防御，防目录穿越
    let rel = normalized.trim_start_matches('/');
    let candidate = static_dir.join(rel);
    let canon = match tokio::fs::canonicalize(&candidate).await {
        Ok(c) => c,
        Err(_) => {
            // 文件不存在则 spa fallback，前端路由路径，条件头(If-None-Match等)对index.html同样生效
            return serve_index_fallback(&static_dir, &static_canon, req, &trace_id).await;
        }
    };
    if !canon.starts_with(&static_canon) {
        warn!(path = %normalized, "静态路径越界(canonicalize 防御拦截)");
        return error_response(GatewayError::NotFound(normalized), &trace_id);
    }

    // ServeDir oneshot取文件(目录自动append index.html，304由其处理)，
    //    Request<Body> 不可Clone，404分支构造新请求并拷贝条件头
    let conditional_headers = req.headers().clone();
    let serve_dir = ServeDir::new(&static_dir).append_index_html_on_directories(true);
    let resp = match serve_dir.oneshot(req).await {
        Ok(r) => r,
        Err(inf) => match inf {}, // ServeDir的Error = Infallible，不可达
    };
    let mut resp = resp.map(Body::new);

    // 未命中则 spa fallback
    if resp.status() == StatusCode::NOT_FOUND {
        let mut fallback_req = Request::builder()
            .method(Method::GET)
            .uri("/index.html")
            .body(Body::empty())
            .unwrap_or_else(|_| Request::new(Body::empty()));
        *fallback_req.headers_mut() = conditional_headers;
        return serve_index_fallback(&static_dir, &static_canon, fallback_req, &trace_id).await;
    }

    // Cache-Control分级，etag缺失时补齐。目录请求实际输出index.html 则 no-store
    //    (index.html必须no-store，不能按普通文件asset_max_age_secs处理，
    //    hash资源/普通资源max-age取配置static.hash_max_age_secs / asset_max_age_secs)
    let is_index = canon.is_dir() || canon.file_name().is_some_and(|n| n == "index.html");
    let cache = if is_index {
        "no-store".to_string()
    } else {
        cache_control_for(
            &normalized,
            state.cfg.static_cfg.hash_max_age_secs,
            state.cfg.static_cfg.asset_max_age_secs,
        )
    };
    resp.headers_mut().insert(
        CACHE_CONTROL,
        HeaderValue::from_str(&cache).expect("Cache-Control 头值构造不可失败：仅数字与合法 token 字符"),
    );
    if !resp.headers().contains_key(ETAG)
        && let Ok(meta) = std::fs::metadata(&canon)
        && let etag = make_etag(&meta)
        && let Ok(hv) = HeaderValue::from_str(&etag)
    {
        resp.headers_mut().insert(ETAG, hv);
    }
    resp
}

/// spa history fallback，返回`index.html`(no-store + `ETag` 304)，`index.html`缺失则 404，
/// req仅用条件头做304判断(`ServeFile`忽略uri)
async fn serve_index_fallback(
    static_dir: &Path,
    static_canon: &Path,
    req: Request<Body>,
    trace_id: &str,
) -> Response<Body> {
    let index = static_dir.join("index.html");
    match tokio::fs::canonicalize(&index).await {
        Ok(c) if c.starts_with(static_canon) => {}
        _ => {
            return error_response(GatewayError::NotFound("index.html".to_string()), trace_id);
        }
    }

    let resp = match ServeFile::new(&index).oneshot(req).await {
        Ok(r) => r,
        Err(inf) => match inf {}, // ServeFile的Error = Infallible，不可达
    };
    let mut resp = resp.map(Body::new);

    // index.html，no-store(spa入口页不缓存，no-store允许存储并revalidate，
    // 部分浏览器/标签页场景仍命中旧缓存导致加载旧bundle，no-store每次访问必拿最新)
    resp.headers_mut()
        .insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    if !resp.headers().contains_key(ETAG)
        && let Ok(meta) = std::fs::metadata(&index)
        && let etag = make_etag(&meta)
        && let Ok(hv) = HeaderValue::from_str(&etag)
    {
        resp.headers_mut().insert(ETAG, hv);
    }
    resp
}

/// `ETag` = "\"{:x}-{:x}\""，mtime秒 + 文件大小，nginx风格弱一致性
pub fn make_etag(meta: &std::fs::Metadata) -> String {
    let mtime_secs = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("\"{mtime_secs:x}-{:x}\"", meta.len())
}

/// `Cache-Control`分级(max-age取配置`static.hash_max_age_secs` / `asset_max_age_secs`)，
///   `index.html` 则 "no-store"，spa更新语义不变量，不配置化，带hash资源 则
///   "public, max-age={hash_max_age_secs}, immutable"，其余 "public, max-age={asset_max_age_secs}"
pub fn cache_control_for(path: &str, hash_max_age_secs: u64, asset_max_age_secs: u64) -> String {
    if path.ends_with("index.html") {
        "no-store".to_string()
    } else if is_hashed_asset(path) {
        format!("public, max-age={hash_max_age_secs}, immutable")
    } else {
        format!("public, max-age={asset_max_age_secs}")
    }
}

/// 判定是否hash文件名资源，扩展名 ∈ {js,css,png,jpg,jpeg,svg,ico,woff,woff2,ttf,eot}且
/// 主文件名含hash段，
///   - '.' 分段整体为8+ 位纯十六进制(如`app.3f9a2c1d.js`、`app.3f9a2c1d.min.css`)，
///   - 'name-<hash>.ext' 形态，'-' 分隔的尾段为8+ 位base64url风格hash(如
///     `index-nK2IYyuW.js`、`vendor-DKX9Ih78.min.css`，K/I/Y/W等非hex字符)。
/// 
/// 两类命中均为immutable缓存。误判边界，形如`my-nicelibrary.js`的8+ 位字母尾段
/// 会被视为hash，immutable缓存，与hex规则同类取舍，文档化接受。
pub fn is_hashed_asset(path: &str) -> bool {
    const HASHED_EXTS: &[&str] = &[
        "js", "css", "png", "jpg", "jpeg", "svg", "ico", "woff", "woff2", "ttf", "eot",
    ];
    let file_name = path.rsplit('/').next().unwrap_or(path);
    let (stem, ext) = match file_name.rsplit_once('.') {
        Some((s, e)) => (s, e),
        None => return false,
    };
    if !HASHED_EXTS.contains(&ext.to_ascii_lowercase().as_str()) {
        return false;
    }

    // 逐 '.' 分段判定(app.min-abcdefgh.css等分段命名同样命中)，
    //   -分段整体为8+ 位纯hex(app.3f9a2c1d.js)，
    //   -分段内 '-' 分隔的尾段为8+ 位base64url(index-nK2IYyuW.js、
    //     vendor-DKX9Ih78.min.css，K/I/Y/W等非hex字符)
    stem.split('.').any(|seg| {
        if seg.len() >= 8 && seg.chars().all(|c| c.is_ascii_hexdigit()) {
            return true;
        }
        seg.split('-').next_back().is_some_and(|hash| {
            hash.len() >= 8 && hash.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::test_state_with;

    #[test]
    fn etag_format_nginx_style() {
        let meta = std::fs::metadata(file!()).expect("测试源文件应存在");
        let etag = make_etag(&meta);
        assert!(etag.starts_with('"'), "ETag 必须带引号");
        assert!(etag.ends_with('"'));
        let inner = &etag[1..etag.len() - 1];
        assert!(inner.contains('-'), "格式 mtime_secs-len");
    }

    #[test]
    fn cache_control_index_no_cache() {
        assert_eq!(cache_control_for("/index.html", 31_536_000, 3600), "no-store");
        assert_eq!(
            cache_control_for("/sub/index.html", 31_536_000, 3600),
            "no-store"
        );
    }

    #[test]
    fn cache_control_hashed_immutable() {
        assert_eq!(
            cache_control_for("/assets/app.3f9a2c1d.js", 31_536_000, 3600),
            "public, max-age=31536000, immutable"
        );
        // Vite真实命名，base64url hash同样immutable 1年
        assert_eq!(
            cache_control_for("/assets/index-nK2IYyuW.js", 31_536_000, 3600),
            "public, max-age=31536000, immutable"
        );
        assert_eq!(
            cache_control_for("/assets/app-DKX9Ih78.js", 31_536_000, 3600),
            "public, max-age=31536000, immutable"
        );
        assert_eq!(
            cache_control_for("/assets/app.js", 31_536_000, 3600),
            "public, max-age=3600"
        );
        assert_eq!(
            cache_control_for("/login", 31_536_000, 3600),
            "public, max-age=3600"
        );
        // 配置化的max-age生效，部署方可按发布节奏收紧
        assert_eq!(
            cache_control_for("/assets/app.3f9a2c1d.js", 604_800, 300),
            "public, max-age=604800, immutable"
        );
        assert_eq!(cache_control_for("/assets/app.js", 604_800, 300), "public, max-age=300");
    }

    #[test]
    fn is_hashed_asset_cases() {
        assert!(is_hashed_asset("/assets/app.3f9a2c1d.js"));
        assert!(is_hashed_asset("/assets/app.3f9a2c1d.min.css"));
        assert!(is_hashed_asset("/img/logo.deadbeef1234.png"));
        // Vite真实命名，base64url风格hash，'-' 分隔，含非hex字符K/I/Y/W等
        assert!(is_hashed_asset("/assets/index-nK2IYyuW.js"), "Vite index 资源");
        assert!(is_hashed_asset("/assets/app-DKX9Ih78.js"), "Vite app 资源");
        assert!(is_hashed_asset("/assets/vendor-DKX9Ih78.min.css"));
        assert!(is_hashed_asset("/assets/app-DKX9Ih78_2.js"), "hash 可含下划线");
        assert!(is_hashed_asset("/assets/app-3f9a2c1d.js"), "'-' 分隔纯 hex 同样命中");
        // 非hash名 / 短hash / 非白名单扩展
        assert!(!is_hashed_asset("/assets/app.js"));
        assert!(!is_hashed_asset("/assets/app.3f9a2c1.js"), "7 位不足 8 位");
        assert!(
            !is_hashed_asset("/assets/app.3f9a2c1d.html"),
            "html 不在白名单"
        );
        assert!(!is_hashed_asset("/index.html"));
        // Vite风格负例，尾段过短 / 非hash段在中间
        assert!(!is_hashed_asset("/assets/app-abc.js"), "尾段 3 位不足 8 位");
        assert!(
            !is_hashed_asset("/assets/my-nicelib.js"),
            "尾段 nicelib 7 位不足 8 位"
        );
        assert!(!is_hashed_asset("/assets/report-2026-q1.js"), "尾段 q1 过短");
        assert!(!is_hashed_asset("/assets/index-nK2IYyuW.html"), "html 不在白名单");
        // 大小写，扩展名与hex均大小写不敏感
        assert!(is_hashed_asset("/assets/app.3F9A2C1D.JS"));
        assert!(is_hashed_asset("/img/logo.DeadBeef.png"));
        assert!(is_hashed_asset("/assets/APP-DKX9Ih78.JS"), "Vite 风格大小写不敏感");
        // 无扩展名则非 hash 资源
        assert!(!is_hashed_asset("/assets/app.3f9a2c1d"));
        assert!(!is_hashed_asset("/assets/index-nK2IYyuW"));
    }

    /// 构造临时静态目录 + 测试状态(`index.html` / hash资源 / 敏感文件 / dotfile)
    fn static_fixture() -> (tempfile_like::TempDir, std::path::PathBuf) {
        // 用进程内自增序号保证并行测试互不冲突
        use std::sync::atomic::{AtomicU64, Ordering};
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "fms_rgw-static-test-{}-{}",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(dir.join("assets")).expect("创建静态目录");
        std::fs::write(dir.join("index.html"), "<html>index</html>").expect("写 index.html");
        std::fs::write(dir.join("assets/app.3f9a2c1d.js"), "console.log(1)").expect("写 hash 资源");
        std::fs::write(dir.join("assets/index-nK2IYyuW.js"), "console.log(2)").expect("写 Vite hash 资源");
        std::fs::write(dir.join(".env"), "SECRET=1").expect("写 dotfile");
        std::fs::write(dir.join("secret.key"), "key").expect("写敏感文件");
        (tempfile_like::TempDir(dir.clone()), dir)
    }

    #[tokio::test]
    async fn static_handler_serves_files_with_cache_control() {
        let (_guard, dir) = static_fixture();
        let mut cfg = Config::default();
        cfg.static_cfg.dir = dir.to_string_lossy().into_owned();
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .fallback(static_handler)
            .with_state(state);
        // hash资源则 immutable 1年 + ETag
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/assets/app.3f9a2c1d.js")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        assert_eq!(
            resp.headers()
                .get(CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("public, max-age=31536000, immutable")
        );
        assert!(resp.headers().contains_key(ETAG), "静态文件必须带 ETag");
        // Vite真实命名hash资源同样immutable 1年
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/assets/index-nK2IYyuW.js")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        assert_eq!(
            resp.headers()
                .get(CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("public, max-age=31536000, immutable")
        );
        // index.html 则 no-store
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/index.html")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::OK);
        assert_eq!(
            resp.headers()
                .get(CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("no-store")
        );
    }

    #[tokio::test]
    async fn static_handler_blocks_sensitive_exts_and_api_variants() {
        // 敏感扩展名黑名单 + /api前缀大小写/解码变体
        let (_guard, dir) = static_fixture();
        std::fs::write(dir.join("backup.sql"), "sql-secret").expect("写 sql");
        std::fs::write(dir.join("app.bak"), "bak-secret").expect("写 bak");
        std::fs::write(dir.join("config.php~"), "php-secret").expect("写 php 备份");
        std::fs::write(dir.join("package.json"), "{\"name\":\"x\"}").expect("写 json");
        std::fs::write(dir.join("note.log"), "log-secret").expect("写 log");
        let mut cfg = Config::default();
        cfg.static_cfg.dir = dir.to_string_lossy().into_owned();
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .fallback(static_handler)
            .with_state(state);
        // 黑名单扩展一律404，不再200下载
        for uri in [
            "/backup.sql",
            "/app.bak",
            "/config.php~",
            "/package.json",
            "/note.log",
        ] {
            let resp = app
                .clone()
                .oneshot(
                    axum::http::Request::builder()
                        .uri(uri)
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(
                resp.status(),
                axum::http::StatusCode::NOT_FOUND,
                "{uri} 应 404"
            );
        }
        // /api前缀大小写 + 编码变体则 404，不落spa fallback
        for uri in [
            "/API/vouchers",
            "/Api/vouchers",
            "/api%2Fvouchers",
            "/API%2Fvouchers",
        ] {
            let resp = app
                .clone()
                .oneshot(
                    axum::http::Request::builder()
                        .uri(uri)
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(
                resp.status(),
                axum::http::StatusCode::NOT_FOUND,
                "{uri} 应 404"
            );
        }
        // 白名单不受影响，hash资源仍200
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/assets/app.3f9a2c1d.js")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "hash 资源仍可服务"
        );
    }

    #[tokio::test]
    async fn static_handler_spa_fallback_and_denials() {
        let (_guard, dir) = static_fixture();
        let mut cfg = Config::default();
        cfg.static_cfg.dir = dir.to_string_lossy().into_owned();
        let state = test_state_with(cfg);
        let app = axum::Router::new()
            .fallback(static_handler)
            .with_state(state);
        // spa history fallback，不存在的前端路由则回 index.html，no-store
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/reports/2026/q1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::OK,
            "SPA fallback 返回 index.html"
        );
        assert_eq!(
            resp.headers()
                .get(CACHE_CONTROL)
                .and_then(|v| v.to_str().ok()),
            Some("no-store")
        );
        let body = http_body_util::BodyExt::collect(resp.into_body())
            .await
            .unwrap()
            .to_bytes();
        assert!(
            String::from_utf8_lossy(&body).contains("index"),
            "fallback 内容应为 index.html"
        );
        // dotfile拒绝
        for path in ["/.env", "/assets/.hidden"] {
            let resp = app
                .clone()
                .oneshot(
                    axum::http::Request::builder()
                        .uri(path)
                        .body(Body::empty())
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(
                resp.status(),
                axum::http::StatusCode::NOT_FOUND,
                "{path} 应 404"
            );
        }
        // 敏感扩展名拒绝
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/secret.key")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::NOT_FOUND,
            "*.key 应 404"
        );
        // /api/ 前缀永不落到静态
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/api/anything")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(
            resp.status(),
            axum::http::StatusCode::NOT_FOUND,
            "/api/ 不应落到静态"
        );
        // 路径穿越，filter二次校验则 400
        let resp = app
            .clone()
            .oneshot(
                axum::http::Request::builder()
                    .uri("/a/../index.html")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), axum::http::StatusCode::BAD_REQUEST);
    }
}

/// 迷你临时目录守卫，测试用，析构时递归删除
#[cfg(test)]
mod tempfile_like {
    pub struct TempDir(pub std::path::PathBuf);
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}
