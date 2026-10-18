import { api } from '../api'
import { useRemote, RemoteNotice } from './ManagementShared'

const kinds: Record<string, string> = { ASSET: '台账', OPERATION: '工单', REGISTRATION: '登记', HANDOVER: '交接', WARRANTY: '保修', PROCUREMENT: '采购', INVENTORY: '盘点', AUDIT: '操作审计' }
export function AssetHistoryPanel({ assetId }: { assetId: number }) {
  const remote = useRemote(() => api.assetHistory(assetId), [assetId])
  return <section className="workflow-page"><header className="workflow-head"><h3>资产履历</h3><button className="button ghost" disabled={remote.loading} onClick={remote.refresh}>刷新履历</button></header><RemoteNotice {...remote} />{remote.data && <><p>{remote.data.notice}</p><section className="workflow-history">{remote.data.events.map((event, index) => <div key={index}><time>{new Date(event.occurredAt).toLocaleString('zh-CN')}</time><strong>{kinds[event.kind] || event.kind} #{event.referenceId} · {event.actor}</strong><span className="workflow-pre">{event.description}</span></div>)}</section></>}</section>
}
