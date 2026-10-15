import type { Asset, AssetFilter, DashboardOverview } from '../types'
import { ArchiveIcon, BoxesIcon, CheckIcon, DashboardIcon, PlusIcon, RefreshIcon, UserIcon, WrenchIcon } from './Icons'

interface Props {
  assets: Asset[]
  loading: boolean
  canCreate: boolean
  canView: boolean
  overview: DashboardOverview | null
  onRefresh: () => void
  onCreate: () => void
  onViewAssets: (filter: AssetFilter) => void
  onSelect: (asset: Asset) => void
}

type RankedItem = { label: string; count: number; percent: number }

const isComputer = (asset: Asset) => asset.category.assetProfile === 'COMPUTER'
const percent = (value: number, total: number) => total ? Math.round(value / total * 100) : 0
const text = (value: string | null | undefined) => value?.trim() || '未填写'

function Ranking({ title, items, tone }: { title: string; items: RankedItem[]; tone: string }) {
  return <div className={`cockpit-ranking ${tone}`}>
    <div className="cockpit-ranking-head"><h3>{title}</h3><span>数量分布</span></div>
    <div>{items.length === 0 ? <span className="cockpit-empty-copy">暂无数据</span> : items.map((item) => <div className="cockpit-rank-row" key={item.label}>
      <div><strong title={item.label}>{item.label}</strong><span>{item.count} 台</span></div>
      <div className="cockpit-progress"><i style={{ width: `${item.percent}%` }} /></div>
    </div>)}</div>
  </div>
}

const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
}).format(new Date(value))

export function ComputerDashboard({ assets, overview, loading, canCreate, canView, onRefresh, onCreate, onViewAssets, onSelect }: Props) {
  const computers = assets.filter(isComputer)
  const count = overview?.computerCount ?? 0
  const displayCount = overview?.displayCount ?? 0
  const available = overview?.available ?? 0, checkedOut = overview?.checkedOut ?? 0
  const maintenance = overview?.maintenance ?? 0, scrapped = overview?.scrapped ?? 0
  const activeTotal = Math.max(1, count)
  const configurationRate = overview?.configurationRate ?? 0, completeComputers = overview?.completeComputers ?? 0
  const missingImages = overview?.missingImages ?? 0, missingDepartments = overview?.missingDepartments ?? 0
  const unboundDisplays = overview?.unboundDisplays ?? 0
  const boundDisplays = displayCount - unboundDisplays
  const recent = [...computers].sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt)).slice(0, 6)
  const today = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' }).format(new Date())
  const availableEnd = percent(available, activeTotal)
  const checkedOutEnd = availableEnd + percent(checkedOut, activeTotal)
  const maintenanceEnd = checkedOutEnd + percent(maintenance, activeTotal)
  const ring = `conic-gradient(#15b795 0 ${availableEnd}%, #f2a228 ${availableEnd}% ${checkedOutEnd}%, #7b61e8 ${checkedOutEnd}% ${maintenanceEnd}%, #d8dee8 ${maintenanceEnd}% 100%)`

  const metrics: Array<{ label: string; value: number; detail: string; filter: AssetFilter; tone: string; icon: typeof BoxesIcon }> = [
    { label: '电脑资产', value: count, detail: `全部资产 ${overview?.total ?? 0} 项`, filter: 'all', tone: 'blue', icon: BoxesIcon },
    { label: '当前可用', value: available, detail: `可用率 ${percent(available, count)}%`, filter: 'available', tone: 'green', icon: CheckIcon },
    { label: '已经领出', value: checkedOut, detail: `领用率 ${percent(checkedOut, count)}%`, filter: 'checkedOut', tone: 'orange', icon: UserIcon },
    { label: '维护中', value: maintenance, detail: maintenance ? '需要跟进处理' : '当前无维护设备', filter: 'maintenance', tone: 'violet', icon: WrenchIcon },
    { label: '已报废', value: scrapped, detail: scrapped ? '保留历史档案' : '当前无报废设备', filter: 'scrapped', tone: 'slate', icon: ArchiveIcon },
  ]

  return <section className={`computer-cockpit ${loading ? 'loading' : ''}`} aria-busy={loading}>
    <header className="cockpit-heading">
      <div className="cockpit-heading-copy"><span className="cockpit-heading-kicker">COMPUTER ASSET COCKPIT</span><h1>驾驶舱</h1><p><span className="cockpit-heading-date">{today}</span><i className="cockpit-heading-divider" />聚焦电脑配置、使用状态与配套设备</p></div>
      <div className="cockpit-heading-side">
        <div className="cockpit-heading-scope"><span className="cockpit-scope-dot" />资产全景<i />状态清晰<i />快速行动</div>
      <div className="cockpit-heading-actions">
        <button className="cockpit-secondary-action" type="button" onClick={onRefresh}><RefreshIcon />刷新数据</button>
        {canCreate && <button className="cockpit-primary-action" type="button" onClick={onCreate}><PlusIcon /><span>登记电脑</span></button>}
      </div>
      </div>
    </header>

    <div className="cockpit-metrics">{metrics.map((metric) => {
      const MetricIcon = metric.icon
      return <button className={`cockpit-metric ${metric.tone}`} type="button" key={metric.label} aria-label={`${metric.label}，${metric.detail}`} onClick={() => canView && onViewAssets(metric.filter)}>
        <span className="cockpit-metric-icon"><MetricIcon /></span><span className="cockpit-metric-copy"><b>{metric.label}</b><strong>{metric.value}</strong><small>{metric.detail}</small></span>
      </button>
    })}</div>

    <div className="cockpit-layout">
      <section className="cockpit-card cockpit-status-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>电脑状态</span><h2>使用情况</h2></div>{canView && <button type="button" onClick={() => onViewAssets('all')}>查看授权范围资产</button>}</div>
        <div className="cockpit-status-body">
          <div className="cockpit-ring" style={{ background: ring }}><div><strong>{count}</strong><span>电脑总量</span></div></div>
          <div className="cockpit-status-list">
            <div><i className="available" /><span>当前可用</span><b>{available}</b><small>{percent(available, count)}%</small></div>
            <div><i className="checked" /><span>已经领出</span><b>{checkedOut}</b><small>{percent(checkedOut, count)}%</small></div>
            <div><i className="maintenance" /><span>维护中</span><b>{maintenance}</b><small>{percent(maintenance, count)}%</small></div>
            <div><i className="scrapped" /><span>已报废</span><b>{scrapped}</b><small>{percent(scrapped, count)}%</small></div>
          </div>
        </div>
      </section>

      <section className="cockpit-card cockpit-health-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>档案质量</span><h2>配置完整度</h2></div><b className="cockpit-health-score">{configurationRate}%</b></div>
        <div className="cockpit-health-progress"><i style={{ width: `${configurationRate}%` }} /></div>
        <p>按 CPU、内存和硬盘三项关键配置计算</p>
        <div className="cockpit-health-grid">
          <div><span>配置完整</span><strong>{completeComputers}</strong><small>台电脑</small></div>
          <div><span>缺少配置</span><strong>{count - completeComputers}</strong><small>台电脑</small></div>
          <div><span>缺少图片</span><strong>{missingImages}</strong><small>台电脑</small></div>
          <div><span>缺少部门</span><strong>{missingDepartments}</strong><small>台电脑</small></div>
        </div>
      </section>

      <section className="cockpit-card cockpit-config-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>硬件画像</span><h2>主流电脑配置</h2></div><span className="cockpit-card-note">按电脑数量排序</span></div>
        <div className="cockpit-config-columns">
          <Ranking title="CPU" items={overview?.cpu ?? []} tone="cpu" />
          <Ranking title="内存" items={overview?.memory ?? []} tone="memory" />
          <Ranking title="硬盘" items={overview?.storage ?? []} tone="storage" />
        </div>
      </section>

      <section className="cockpit-card cockpit-support-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>配套设备</span><h2>显示器绑定</h2></div><span className="cockpit-card-symbol"><DashboardIcon /></span></div>
        <div className="cockpit-support-total"><strong>{displayCount}</strong><span>台显示器资产</span></div>
        <div className="cockpit-support-line"><span><i className="bound" />已绑定电脑</span><strong>{boundDisplays}</strong></div>
        <div className="cockpit-support-line"><span><i className="unbound" />暂未绑定</span><strong>{unboundDisplays}</strong></div>
        <div className="cockpit-support-progress"><i style={{ width: `${percent(boundDisplays, displayCount)}%` }} /></div>
        <small>绑定率 {percent(boundDisplays, displayCount)}%</small>
      </section>

      <section className="cockpit-card cockpit-location-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>资产分布</span><h2>部门与位置</h2></div></div>
        <div className="cockpit-location-columns">
          <Ranking title="归属部门" items={overview?.departments ?? []} tone="department" />
          <Ranking title="存放位置" items={overview?.locations ?? []} tone="location" />
        </div>
      </section>

      {canView && <section className="cockpit-card cockpit-recent-card">
        <div className="cockpit-card-head"><div className="cockpit-card-title"><span>授权范围内的动态</span><h2>最近更新的电脑</h2></div><button type="button" onClick={() => onViewAssets('all')}>查看电脑</button></div>
        <div className="cockpit-recent-list">{recent.length === 0 ? <div className="cockpit-empty-copy">暂无电脑资产</div> : recent.map((asset) => <button type="button" key={asset.id} onClick={() => onSelect(asset)}>
          <span className="cockpit-device-mark">{asset.name.slice(0, 1).toUpperCase()}</span>
          <span><strong>{asset.name}</strong><small>{asset.assetTag} · {text(asset.model.name)}</small></span>
          <span className="cockpit-device-config">{[asset.cpu, asset.memory, asset.storage].filter(Boolean).join(' · ') || '配置未填写'}</span>
          <time>{formatDate(asset.updatedAt)}</time>
        </button>)}</div>
      </section>}
    </div>
    {loading && <div className="cockpit-loading"><span className="spinner" />正在汇总电脑资产…</div>}
  </section>
}
