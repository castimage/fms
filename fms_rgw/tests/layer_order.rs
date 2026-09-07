//! 路由装配次序锁定(axum layer/`route_layer`语义)
//!
//! layer后加者为最外层(`mw_access_log`位于cors之外、先执行)，`route_layer`仅作用于
//! 调用前已注册的路由，`build_router`的装配注释依赖此语义，防重构改乱层序。
#![forbid(unsafe_code)]

use axum::body::Body;
use axum::extract::State;
use axum::http::{Request, Response};
use axum::middleware::Next;
use axum::Router;
use std::sync::{Arc, Mutex};
use tower::ServiceExt;

async fn mw_a(
    State(log): State<Arc<Mutex<Vec<String>>>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    log.lock().unwrap().push("A-in".to_string());
    let resp = next.run(req).await;
    log.lock().unwrap().push("A-out".to_string());
    resp
}

async fn mw_b(
    State(log): State<Arc<Mutex<Vec<String>>>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    log.lock().unwrap().push("B-in".to_string());
    let resp = next.run(req).await;
    log.lock().unwrap().push("B-out".to_string());
    resp
}

async fn mw_x(
    State(log): State<Arc<Mutex<Vec<String>>>>,
    req: Request<Body>,
    next: Next,
) -> Response<Body> {
    log.lock().unwrap().push("X-in".to_string());
    next.run(req).await
}

/// layer后加者为最外层，B，后加先于A，先加执行
#[tokio::test]
async fn layer_later_added_is_outermost() {
    let log: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let app = Router::new()
        .route("/", axum::routing::get(|| async { "ok" }))
        .layer(axum::middleware::from_fn_with_state(log.clone(), mw_a))
        .layer(axum::middleware::from_fn_with_state(log.clone(), mw_b))
        .with_state(log.clone());
    let _ = app
        .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let order = log.lock().unwrap().clone();
    assert_eq!(order, vec!["B-in", "A-in", "A-out", "B-out"]);
}

/// `route_layer`仅作用于调用前已注册的路由，后加者为外层，
/// /first(注册于两次`route_layer`之前)套X与B两层，/second(注册于第二次
/// `route_layer`之前)仅套B层
#[tokio::test]
async fn route_layer_covers_only_routes_added_before() {
    let log: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let app = Router::new()
        .route("/first", axum::routing::get(|| async { "f" }))
        .route_layer(axum::middleware::from_fn_with_state(log.clone(), mw_x))
        .route("/second", axum::routing::get(|| async { "s" }))
        .route_layer(axum::middleware::from_fn_with_state(log.clone(), mw_b))
        .with_state(log.clone());
    let _ = app
        .clone()
        .oneshot(Request::builder().uri("/first").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let first_order = log.lock().unwrap().clone();
    assert_eq!(first_order, vec!["B-in", "X-in", "B-out"]);
    log.lock().unwrap().clear();
    let _ = app
        .oneshot(Request::builder().uri("/second").body(Body::empty()).unwrap())
        .await
        .unwrap();
    let second_order = log.lock().unwrap().clone();
    assert_eq!(second_order, vec!["B-in", "B-out"]);
}
