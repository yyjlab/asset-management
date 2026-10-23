import type { Asset, DomainJoinStatus, DomainStatusOption } from './types'

export const domainStatusOptions: DomainStatusOption[] = [
  { code: 'JOINED', label: '已入域', description: '已由运维确认加入公司域。', sortOrder: 10, version: null },
  { code: 'NOT_JOINED', label: '未入域', description: '尚未加入公司域，需要先完成加域。', sortOrder: 20, version: null },
  { code: 'UNKNOWN', label: '待确认', description: '尚未核实入域情况，请检查设备后再选择。', sortOrder: 30, version: null },
]

export function domainStatusOf(asset: Pick<Asset, 'category' | 'domainJoinStatus'>): DomainJoinStatus | null {
  const name = asset.category.name.toLowerCase()
  const computer = asset.category.assetProfile ? asset.category.assetProfile === 'COMPUTER'
    : !/显示器|屏幕|大屏|display|monitor/.test(name) && /电脑|台式|笔记本|mac|主机|computer/.test(name)
  return computer ? asset.domainJoinStatus || 'UNKNOWN' : null
}

export const matchesDomainStatus = (asset: Asset, filter: DomainJoinStatus | '') => !filter || domainStatusOf(asset) === filter
