import { api } from '../api'
import type { WorkbenchSection } from '../types/management'
import { useRemote, RemoteNotice, stateLabels } from './ManagementShared'

export function WorkbenchPage({ onOpen }: { onOpen: (section: WorkbenchSection, id: number | null, state?: string) => void }) {
  const remote = useRemote(api.workbench)
  return <section className="workflow-page"><header className="workflow-head"><div><h2>我的待办</h2><p>只读取当前账号可处理或需要跟进的事项；不会自动审批和修改台账。</p></div><button className="button ghost" disabled={remote.loading} onClick={remote.refresh}>刷新待办</button></header>
    <RemoteNotice {...remote} />{remote.data && <><p>当前共 {remote.data.total} 项待处理／跟进事项。每类显示最近10项，完整列表可进入对应模块。</p><div className="workflow-grid">{remote.data.groups.map(group => <section className="workflow-card" key={group.section}><header className="workflow-head"><h3>{group.title}（{group.total}）</h3><button className="button ghost" onClick={() => onOpen(group.section, null)}>查看全部</button></header>{group.items.map(item => <div className="workflow-head" key={item.id}><div><strong>{item.title}</strong><p>{stateLabels[item.state] || item.state} · {new Date(item.updatedAt).toLocaleString('zh-CN')}</p></div><button className="button primary" onClick={() => onOpen(group.section, item.id, item.state)}>查看处理</button></div>)}{!group.items.length && <p>暂无待办</p>}</section>)}</div></>}
  </section>
}
