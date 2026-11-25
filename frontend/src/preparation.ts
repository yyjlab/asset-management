import type { Asset } from './types'

export const preparationLabels = {
  UNVERIFIED: '未核验', PENDING: '待整备', IN_PROGRESS: '整备中', READY: '可配发',
  BLOCKED: '异常待处理', RETIREMENT_PENDING: '待报废确认', SCRAPPED: '已报废', CHECKED_OUT: '已领用', UNAVAILABLE: '读取失败',
} as const
export type PreparationState = keyof typeof preparationLabels
export interface PreparationOverview {
  assetId: number; version: number; assetVersion: number; state: PreparationState; reason: string
  previousHolder: string | null; updatedAt: string | null
}
export interface PreparationCheck { result: 'UNCHECKED' | 'PASS' | 'ISSUE' | 'NA'; note: string }
export const preparationChecks = { DATA: '资料与账号', SYSTEM: '系统与软件', DOMAIN: '入域与网络', HARDWARE: '硬件与配件' } as const
export interface PreparationDetail {
  preparation: PreparationOverview; note: string; checks: Record<string, PreparationCheck>
  events: { cycle: number; actor: string; action: string; description: string; occurredAt: string }[]
}
export function preparationOf(asset: Asset, records: PreparationOverview[] | null): PreparationOverview {
  const record = records?.find(item => item.assetId === asset.id)
  const base = { assetId: asset.id, assetVersion: asset.version, version: -1, previousHolder: record?.previousHolder ?? null, updatedAt: record?.updatedAt ?? null }
  if (asset.status.name.includes('报废')) return { ...base, state: 'SCRAPPED', reason: '已报废，不能配发' }
  if (asset.checkedOut) return { ...base, state: 'CHECKED_OUT', reason: '设备仍在领用中' }
  if (records === null) return { ...base, state: 'UNAVAILABLE', reason: '配发状态读取失败，请刷新后核对' }
  if (asset.assignedUserId || asset.assignedTo?.trim()) return { ...base, state: 'BLOCKED', reason: '领用信息尚未清空，请核实实际归还情况' }
  if (record?.state === 'RETIREMENT_PENDING') return record
  if (asset.status.name.includes('维修') || asset.status.name.includes('维护')) {
    const reason = record?.state === 'BLOCKED' && record.version >= 0 && record.assetVersion === asset.version ? record.reason : ''
    return { ...base, state: 'BLOCKED', reason: reason || '设备处于维护中' }
  }
  const defect = asset.knownDefects?.find(item => !item.archivedAt)
  if (defect) return { ...base, state: 'BLOCKED', reason: defect.description || '存在当前有效缺陷，请核实处理' }
  if (!record) return { ...base, state: 'UNVERIFIED', reason: '尚无整备检查记录' }
  if (record.assetVersion !== asset.version) return { ...record, state: 'UNVERIFIED', reason: '资产信息已变化，请重新核验' }
  return record
}
