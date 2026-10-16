import { useEffect, useState } from 'react'
import { api } from '../api'
import type { TaskNotificationStatus } from '../types'
import { RefreshIcon } from './Icons'

export function WorkflowNotifications({ onNotify }: { onNotify: (text: string, kind?: 'ok' | 'error') => void }) {
  const [data, setData] = useState<TaskNotificationStatus | null>(null)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState<number | null>(null)
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    api.taskNotifications().then(result => { if (active) setData(result) })
      .catch(failure => { if (active) { setData(null); setError(failure instanceof Error ? failure.message : '读取通知状态失败') } })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [revision])
  const retry = async (id: number) => {
    setBusy(id)
    try { await api.retryTaskNotification(id); setRevision(value => value + 1); onNotify('通知已重新排队，并不代表已经送达') }
    catch (failure) { onNotify(failure instanceof Error ? failure.message : '重试失败', 'error') }
    finally { setBusy(null) }
  }
  const labels: Record<string, string> = { PENDING: '待发送／等待重试', CLAIMED: '发送中', SENT: '已送达', FAILED: '重试耗尽' }
  return <section className="handover-page"><header className="handover-head"><div><h2>登记与交接通知</h2><p>钉钉群提醒，独立于WUD部署通知。网络投递失败不撤销业务操作。</p></div><button className="button ghost" disabled={loading || busy !== null} onClick={() => setRevision(value => value + 1)}><RefreshIcon />刷新</button></header>
    <div className="handover-notice">在服务器 .env 配置 ASSET_WORKFLOW_NOTIFY_ENABLED、ASSET_WORKFLOW_DINGTALK_WEBHOOK、ASSET_WORKFLOW_DINGTALK_SECRET，再更新后端容器。密钥不在网页填写或显示；请使用仅限相关人员的资产管理群。</div>
    {error ? <p role="alert" className="handover-empty">{error}</p> : loading ? <div className="handover-empty"><span className="spinner" />正在读取通知状态…</div> : data && <>
      <p>通知：{data.enabled ? '已启用' : '未启用'} · 配置：{data.configured ? '格式有效，实际可达性以投递结果为准' : '未配置完整或格式错误'} · 待发送：{data.pending} · 失败：{data.failed}</p>
      <p className="handover-notice">业务进度通知最多每分钟取20条；发送失败最多尝试5次。每天09:00（北京时间）汇总未完结和逾期交接。网络超时后的重试可能重复发送提醒，但不会重复新增资产或执行交接。</p>
      {data.recent.length ? <div className="handover-table-wrap"><table className="handover-table"><thead><tr><th>通知</th><th>状态</th><th>尝试次数</th><th>最近错误</th><th>操作</th></tr></thead><tbody>{data.recent.map(item => <tr key={item.id}><td>#{item.id} · {item.title}</td><td>{labels[item.state] || item.state}</td><td>{item.attempts} / 5</td><td>{item.lastError || '—'}</td><td>{item.state === 'FAILED' && <button className="button ghost" disabled={busy !== null || !data.configured} onClick={() => void retry(item.id)}>重新排队</button>}</td></tr>)}</tbody></table></div> : <div className="handover-empty">暂无通知记录。默认关闭时不会创建或发送通知。</div>}
    </>}
  </section>
}
