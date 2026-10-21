import { useMemo, useState } from 'react'
import type { Asset, AuthUser, LookupValue } from '../types'
import { BoxesIcon, ClipboardIcon, RefreshIcon } from './Icons'
import { HandoverPage } from './HandoverPage'
import { RegistrationPage } from './RegistrationPage'
import { WorkflowNotifications } from './WorkflowNotifications'
import { OperationPage } from './OperationPage'
import { InventoryPage } from './InventoryPage'
import { ConsumablePage } from './ConsumablePage'
import './SelfServicePage.css'

interface Props {
  user: AuthUser
  assets: Asset[]
  lookups: LookupValue[]
  loading: boolean
  error: string
  onRefresh: () => void
  onSelect: (asset: Asset) => void
  onNotify: (text: string, kind?: 'ok' | 'error') => void
  onOpenOa?: () => void
}

export function SelfServicePage({ user, assets, lookups, loading, error, onRefresh, onSelect, onNotify, onOpenOa }: Props) {
  const [tab, setTab] = useState<'assets' | 'handovers' | 'registrations' | 'review' | 'notifications' | 'operations' | 'repair' | 'inventory' | 'consumables'>('assets')
  const canView = user.permissions.includes('ASSET_VIEW')
  const canReview = user.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_CREATE', 'ASSET_CHECKOUT'].every(permission => user.permissions.includes(permission as 'ASSET_VIEW' | 'ASSET_CREATE' | 'ASSET_CHECKOUT'))
  const ownAssets = useMemo(() => canView ? assets.filter(asset => asset.assignedUserId === user.id) : [], [assets, user.id, canView])

  return <section className="self-service-page">
    <header className="self-service-head">
      <div><h2>员工自助</h2><p>{user.displayName}（{user.username}） · 查看本人设备与交接事项</p></div>
      {tab === 'assets' && canView && <button className="button ghost" disabled={loading} onClick={onRefresh}><RefreshIcon />刷新</button>}
    </header>
    <div className="self-service-tabs" role="group" aria-label="员工自助内容">
      {onOpenOa && <button className="button ghost" onClick={onOpenOa}>钉钉 OA 交接</button>}
      <button className={`button ${tab === 'assets' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'assets'} onClick={() => setTab('assets')}><BoxesIcon />我的资产</button>
      <button className={`button ${tab === 'handovers' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'handovers'} onClick={() => setTab('handovers')}><ClipboardIcon />我的交接</button>
      <button className={`button ${tab === 'registrations' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'registrations'} onClick={() => setTab('registrations')}>设备登记</button>
      <button className={`button ${tab === 'operations' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'operations'} onClick={() => setTab('operations')}>领用归还／信息纠错</button>
      <button className={`button ${tab === 'repair' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'repair'} onClick={() => setTab('repair')}>故障报修</button>
      <button className={`button ${tab === 'inventory' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'inventory'} onClick={() => setTab('inventory')}>我的盘点</button>
      <button className={`button ${tab === 'consumables' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'consumables'} onClick={() => setTab('consumables')}>耗材领用</button>
      {canReview && <button className={`button ${tab === 'review' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'review'} onClick={() => setTab('review')}>登记审核</button>}
      {user.canManageUsers && <button className={`button ${tab === 'notifications' ? 'primary' : 'ghost'}`} aria-pressed={tab === 'notifications'} onClick={() => setTab('notifications')}>通知状态</button>}
    </div>
    {tab === 'handovers' ? <HandoverPage currentUserId={user.id} assets={[]} selfService onNotify={onNotify} />
      : tab === 'operations' || tab === 'repair' ? <OperationPage key={tab} user={user} assets={assets} repair={tab === 'repair'} onNotify={onNotify} onAssetsChanged={onRefresh} />
        : tab === 'inventory' ? <InventoryPage user={user} onNotify={onNotify} />
          : tab === 'consumables' ? <ConsumablePage user={user} onNotify={onNotify} />
      : tab === 'registrations' || tab === 'review' && canReview ? <RegistrationPage key={tab} user={user} review={tab === 'review'} assets={assets} lookups={lookups} onNotify={onNotify} onAssetsChanged={onRefresh} />
        : tab === 'notifications' && user.canManageUsers ? <WorkflowNotifications onNotify={onNotify} />
      : <>
        <p className="self-service-notice">仅显示已关联本账号且在授权范围内的资产，不按姓名匹配。缺少设备或信息有误时，请联系管理员核对；此处不会自动变更领用记录。</p>
        {!canView ? <div className="self-service-empty">当前账号未获查看资产权限，请联系管理员授权；“我的交接”仍可查看与确认本人事项。</div>
          : loading ? <div className="self-service-empty"><span className="spinner" />正在读取本人资产…</div>
            : error ? <div className="self-service-empty" role="alert">资产读取失败：{error}，请点击刷新重试。</div>
              : <>
                <p className="self-service-total">当前可见的本人资产：{ownAssets.length} 项</p>
                {ownAssets.length ? <div className="self-service-assets">{ownAssets.map(asset => <article className="self-service-asset" key={asset.id}>
                  <div className="self-service-asset-head"><div><h3>{asset.name}</h3><span>{asset.assetTag}</span></div><span>{asset.status.name}</span></div>
                  <dl><div><dt>型号</dt><dd>{asset.model.name}</dd></div><div><dt>序列号 SN</dt><dd>{asset.manufacturerSerialNumber || '未填写'}</dd></div><div><dt>分类</dt><dd>{asset.category.name}</dd></div><div><dt>位置</dt><dd>{asset.location.name}</dd></div></dl>
                  <button className="button ghost" onClick={() => onSelect(asset)} aria-label={`查看资产 ${asset.assetTag} 详情`}>查看详情</button>
                </article>)}</div> : <div className="self-service-empty">暂无明确关联本账号的资产。没有记录不代表没有领用设备，请联系管理员核对“关联领用账号”。</div>}
              </>}
      </>}
  </section>
}
