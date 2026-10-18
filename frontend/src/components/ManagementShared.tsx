import { useEffect, useRef, useState } from 'react'
import type { ManagementEvent, Paged } from '../types/management'
import './WorkflowPages.css'

export function useRemote<T>(loader: () => Promise<T>, dependencies: unknown[] = [], enabled = true) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(enabled)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const callback = useRef(loader)
  callback.current = loader
  useEffect(() => {
    let active = true
    if (!enabled) { setData(null); setLoading(false); setError(''); return }
    setLoading(true); setError('')
    callback.current().then(value => { if (active) setData(value) }).catch(failure => { if (active) { setData(null); setError(failure instanceof Error ? failure.message : '读取失败') } }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [enabled, revision, ...dependencies])
  return { data, loading, error, refresh: () => setRevision(value => value + 1) }
}
export const failMessage = (error: unknown) => error instanceof Error ? error.message : '操作失败'
export function RemoteNotice({ loading, error }: { loading: boolean; error: string }) {
  return error ? <p role="alert" className="workflow-error">{error}，请刷新重试。</p> : loading ? <p className="workflow-empty">正在读取…</p> : null
}
export function PageButtons({ data, page, onPage, disabled }: { data: Paged<unknown> | null; page: number; onPage: (page: number) => void; disabled?: boolean }) {
  return <footer className="workflow-pagination"><span>共 {data?.totalElements ?? 0} 条</span><div className="workflow-actions"><button className="button ghost" disabled={disabled || page === 0} onClick={() => onPage(page - 1)}>上一页</button><span>{data?.totalPages ? page + 1 : 0} / {data?.totalPages ?? 0}</span><button className="button ghost" disabled={disabled || !data || page + 1 >= data.totalPages} onClick={() => onPage(page + 1)}>下一页</button></div></footer>
}
export function ManagementHistory({ events }: { events: ManagementEvent[] }) {
  return <section className="workflow-history"><h3>处理记录</h3>{events.map((event, index) => <div key={index}><time>{new Date(event.occurredAt).toLocaleString('zh-CN')}</time><strong>{event.actor}</strong><span className="workflow-pre">{event.description}</span></div>)}</section>
}
export const managementDenied = <p className="workflow-notice">当前账号未获得此功能的管理权限，请联系管理员授权。</p>
export const stateLabels: Record<string, string> = { PENDING: '待受理', SUBMITTED: '待审核', NEEDS_INFO: '待补资料', WAITING_CONFIRM: '待本人确认', PROCESSING: '维修处理中', CONFIRMED: '待验收完结', APPROVED: '待采购下单', ORDERED: '待到货验收', RECEIVED: '已验收入库', COMPLETED: '已完结', REJECTED: '已驳回', CANCELLED: '已取消', IN_PROGRESS: '交接中', OPEN: '盘点中', LOW_STOCK: '库存预警', WARRANTY_DUE: '保修即将到期' }
