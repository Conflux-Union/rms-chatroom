// Namespace: settings modal (Setting.vue).
export const messages = {
  'settings.title': '设置',
  'settings.inputDevice': '输入设备',
  'settings.outputDevice': '输出设备',
  'settings.play': '播放',
  'settings.voiceAnnounce': '进入语音提醒',
  'settings.voiceAnnounceDesc': '有人加入或离开当前语音频道时播报其昵称',
  'settings.hotkeyWindow': '显示/隐藏窗口（全局快捷键）',
  'settings.hotkeyMic': '切换麦克风（全局快捷键）',
  'settings.hotkeyPlaceholder': '点击后按下快捷键',
  'settings.hotkeyTip': '请按下你想要的快捷键组合（例如 Ctrl + Alt + M）',
  'settings.hotkeyDetected': '检测到: {acc}（点击保存以应用）',
  'settings.telemetry': '匿名错误报告',
  'settings.telemetryDesc': '上报崩溃和连接质量数据帮助改进，不含任何消息内容',
  'settings.language': '语言',
  'settings.languageSystem': '跟随系统',
} as const

export type Keys = keyof typeof messages
