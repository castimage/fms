// ccb，FOUC 防闪，进入首帧前按本地主题预置 html.dark，与 stores/theme 的 key 必须一致，
// 独立外部文件，网关 CSP script-src 'self' 拒绝内联脚本(曾实测内联版本被浏览器阻止)，
// 外部静态资源经 'self' 放行(主题初始化失败仅丢深色预置，不影响功能)
;(function () {
  try {
    var t = JSON.parse(localStorage.getItem('fms_theme') || 'null')
    if (t && t.dark) document.documentElement.classList.add('dark')
  } catch (e) {
    /* 本地数据损坏时忽略，走默认浅色 */
  }
})()
