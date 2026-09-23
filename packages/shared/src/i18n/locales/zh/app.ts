// Namespace: app shell — Main.vue, ServerList.vue, Login.vue, Callback.vue,
// NotFound.vue, AppUpdater.vue.
export const messages = {
  // ServerList.vue
  'app.serverMenuPermissions': '权限设置',
  'app.serverMenuDelete': '删除服务器',
  'app.createServer': '创建服务器',
  'app.githubRepo': 'GitHub 仓库',
  'app.settings': '设置',
  'app.serverNamePlaceholder': '服务器名称',
  'app.create': '创建',
  // Main.vue
  'app.newMessage': '新消息',
  'app.sentAttachment': '{name} 发送了附件',
  'app.youAreMuted': '你已被禁言',
  'app.selectChannel': '选择频道',
  'app.noChannelPrompt': '选择一个频道开始聊天',
  // ChangelogDialog.vue
  'app.whatsNew': '更新日志 v{version}',
  'app.changelogNew': '✨ 新增与改进',
  'app.changelogFixes': '🐛 问题修复',
  // Login.vue
  'app.tryingSilentLogin': '正在尝试无感登录...',
  'app.welcomeLogin': '欢迎！请使用 CXU 账号登录',
  'app.pleaseWait': '请稍候...',
  'app.cxuAccountLogin': 'CXU 账号登录',
  // Callback.vue
  'app.loggingInWait': '正在登录，请稍候...',
  // NotFound.vue
  'app.pageNotFound': '页面不存在',
  'app.notFoundHint': '链接无效，或目标服务器 / 频道 / 消息已删除、无权访问。',
  'app.backToHome': '返回首页',
  // AppUpdater.vue
  'app.updateRequired': '需要更新才能继续使用',
  'app.updateAvailable': '发现新版本',
  'app.statusLabel': '状态：{value}',
  'app.versionLabel': '版本：{value}',
  'app.downloading': '下载中',
  'app.downloadingPercent': '下载中：{percent}%',
  'app.unknownSize': '未知大小',
  'app.updateError': '更新出错：{message}',
  'app.quit': '退出',
  'app.downloadUpdate': '下载更新',
  'app.installAndRestart': '安装并重启',
  'app.later': '稍后',
  'app.stateIdle': '空闲',
  'app.stateChecking': '检查更新中',
  'app.stateAvailable': '有新版本（待下载）',
  'app.stateUpToDate': '已是最新版本',
  'app.stateDownloading': '正在下载',
  'app.stateDownloaded': '下载完成',
  'app.stateError': '错误',
  'app.downloadingEllipsis': '下载中…',
  'app.updateStartDownload': '更新（开始下载）',
  'app.update': '更新',
} as const

export type Keys = keyof typeof messages
