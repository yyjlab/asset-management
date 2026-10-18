import { api } from '../api'
import type { AuthUser } from '../types'
import { canManageWorkflows } from './OperationPage'
import { useRemote, RemoteNotice, managementDenied, stateLabels } from './ManagementShared'

export function ManagementReportPage({ user }: { user: AuthUser }) {
  const manager = canManageWorkflows(user); const remote = useRemote(api.managementReports, [], manager)
  if (!manager) return managementDenied
  const report = remote.data
  return <section className="workflow-page"><header className="workflow-head"><div><h2>管理统计报表</h2><p>只读统计，不进行折旧计算、财务过账或台账修正。</p></div><button className="button ghost" disabled={remote.loading} onClick={remote.refresh}>刷新统计</button></header><RemoteNotice {...remote} />{report && <><p className="workflow-notice">{report.notice}</p><p>生成时间：{new Date(report.generatedAt).toLocaleString('zh-CN')}</p><div className="workflow-grid">{[['资产总量', report.assets], ['空闲可分配', report.idle], ['已领用但未关联账号', report.unlinkedCheckedOut], ['耗材低库存', report.lowStock], ['未处理盘点差异', report.openDiscrepancies], ['已完结维修累计费用（元）', Number(report.completedRepairCost).toFixed(2)]].map(([label, value]) => <article className="workflow-card" key={label}><h3>{label}</h3><strong>{value}</strong></article>)}</div><div className="workflow-grid"><section className="workflow-card"><h3>资产状态</h3>{report.statuses.map(item => <p key={item.label}>{item.label}：{item.count}</p>)}</section><section className="workflow-card"><h3>采购进度</h3>{report.procurements.map(item => <p key={item.label}>{stateLabels[item.label] || item.label}：{item.count}</p>)}{!report.procurements.length && <p>暂无采购记录</p>}</section></div><section className="workflow-card"><h3>部门资产占用</h3><div className="workflow-table-wrap"><table className="workflow-table"><thead><tr><th>部门</th><th>资产总量</th><th>已领用</th><th>未领用（不代表可分配）</th></tr></thead><tbody>{report.departments.map(item => <tr key={item.name}><td>{item.name || '未设置'}</td><td>{item.assets}</td><td>{item.checkedOut}</td><td>{item.assets - item.checkedOut}</td></tr>)}</tbody></table></div></section></>}</section>
}
