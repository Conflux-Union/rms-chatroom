// Vocabulary shared across namespaces. Keys are flat dotted strings; the zh
// dictionaries are the source of truth and every en file must cover their keys.
export const messages = {
  'common.ok': '确定',
  'common.cancel': '取消',
  'common.close': '关闭',
  'common.confirm': '确认',
  'common.save': '保存',
  'common.saved': '已保存',
  'common.saveFailed': '保存失败',
  'common.delete': '删除',
  'common.edit': '编辑',
  'common.copy': '复制',
  'common.copied': '已复制',
  'common.retry': '重试',
  'common.loading': '加载中...',
  'common.stop': '停止',
  'common.test': '测试',
  'common.enabled': '已开启',
  'common.disabled': '已关闭',
  'common.systemDefault': '系统默认',
  'common.notice': '提示',
  'common.send': '发送',
  'common.you': '你',
  'common.someone': '其他用户',
  'common.search': '搜索',
  'common.back': '返回',
} as const

export type Keys = keyof typeof messages
