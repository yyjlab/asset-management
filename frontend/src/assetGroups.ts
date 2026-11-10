import type { Asset, AssetProfile, LookupValue, Summary } from './types'

export const assetGroups = [
  { key: 'all', label: '全部资产', profile: null, aliases: [] },
  { key: 'laptop', label: '笔记本电脑', profile: 'COMPUTER', aliases: ['笔记本电脑', '笔记本', '笔记本计算机'] },
  { key: 'desktop', label: '台式电脑', profile: 'COMPUTER', aliases: ['台式电脑', '台式', '台式机', '台式计算机', '桌面电脑'] },
  { key: 'display', label: '显示器', profile: 'DISPLAY', aliases: ['显示器', '显示设备', '屏幕', '大屏'] },
  { key: 'mouse', label: '鼠标', profile: 'GENERAL', aliases: ['鼠标'] },
] as const
export type AssetGroupKey = typeof assetGroups[number]['key']
export const assetGroupOf = (key?: string) => assetGroups.find(group => group.key === key) || assetGroups[0]
const normalized = (name: string) => name.normalize('NFKC').replace(/\s+/g, '').toLowerCase()

export function groupCategories(key: AssetGroupKey, categories: LookupValue[]) {
  const group = assetGroupOf(key)
  return categories.filter(category => category.type === 'CATEGORY' && (key === 'all'
    || (!category.assetProfile || category.assetProfile === group.profile)
      && (group.key === 'display' && category.assetProfile === 'DISPLAY'
        || (group.aliases as readonly string[]).some(name => normalized(name) === normalized(category.name)))))
}

export function groupAssets(key: AssetGroupKey, categories: LookupValue[], assets: Asset[]) {
  if (key === 'all') return assets
  const ids = new Set(groupCategories(key, categories).map(category => category.id))
  return assets.filter(asset => ids.has(asset.category.id))
}

export function groupSummary(assets: Asset[]): Summary {
  const scrapped = (asset: Asset) => asset.status.name.includes('报废')
  const maintenance = (asset: Asset) => asset.status.name.includes('维修') || asset.status.name.includes('维护')
  return { total: assets.length, available: assets.filter(asset => !asset.checkedOut && !scrapped(asset) && !maintenance(asset)).length,
    checkedOut: assets.filter(asset => asset.checkedOut && !scrapped(asset)).length,
    maintenance: assets.filter(maintenance).length, scrapped: assets.filter(scrapped).length }
}

export const groupProfile = (key: AssetGroupKey): AssetProfile | null => assetGroupOf(key).profile
