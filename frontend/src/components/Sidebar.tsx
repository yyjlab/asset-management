import { useState } from 'react'
import type { AssetFilter, Permission } from '../types'
const version = 'v26.915.1651'
import { BoxesIcon, ClipboardIcon, DashboardIcon, SettingsIcon, SidebarPanelIcon, UploadIcon, UserIcon, UsersIcon } from './Icons'

export type AppSection = 'dashboard' | 'workbench' | 'selfService' | 'assets' | 'handover' | 'operations' | 'inventory' | 'repair' | 'consumables' | 'procurement' | 'kits' | 'support' | 'reports' | 'registrations' | 'transfer' | 'users' | 'logs' | 'settings'

interface Props {
  section: AppSection
  canManageUsers: boolean
  dingtalkLogin: boolean
  permissions: Permission[]
  onFilter: (filter: AssetFilter) => void
  onSection: (section: AppSection) => void
}

const sidebarStorageKey = 'asset-center-sidebar-collapsed'

const initiallyCollapsed = () => {
  try { return window.localStorage.getItem(sidebarStorageKey) === 'true' }
  catch { return false }
}

export function Sidebar({ section, canManageUsers, dingtalkLogin, permissions, onFilter, onSection }: Props) {
  const [collapsed, setCollapsed] = useState(initiallyCollapsed)
  const toggleSidebar = () => setCollapsed((current) => {
    const next = !current
    try { window.localStorage.setItem(sidebarStorageKey, String(next)) } catch { /* Browser storage may be unavailable. */ }
    return next
  })

  return (
    <aside className={`sidebar ${collapsed ? 'collapsed' : ''}`}>
      <div className="brand">
        <div className="brand-identity">
          <strong>资产中心</strong>
          <span className="app-version" aria-label={`系统版本 ${version}`}>{version}</span>
        </div>
        <button type="button" className="sidebar-toggle" aria-label={collapsed ? '展开侧栏' : '收起侧栏'} aria-expanded={!collapsed} title={collapsed ? '展开侧栏' : '收起侧栏'} onClick={toggleSidebar}>
          <SidebarPanelIcon />
        </button>
      </div>
      <nav>
        <button aria-label="驾驶舱" title={collapsed ? '驾驶舱' : undefined} className={`nav-item ${section === 'dashboard' ? 'active' : ''}`} onClick={() => onSection('dashboard')}>
          <DashboardIcon /><span>驾驶舱</span>
        </button>
        <button aria-label="我的待办" title={collapsed ? '我的待办' : undefined} className={`nav-item ${section === 'workbench' ? 'active' : ''}`} onClick={() => onSection('workbench')}><ClipboardIcon /><span>我的待办</span></button>
        <button aria-label="员工自助" title={collapsed ? '员工自助' : undefined} className={`nav-item ${section === 'selfService' ? 'active' : ''}`} onClick={() => onSection('selfService')}>
          <UserIcon /><span>员工自助</span>
        </button>
        {(dingtalkLogin || permissions.includes('ASSET_VIEW')) && <button aria-label="资产" title={collapsed ? '资产' : undefined} className={`nav-item ${section === 'assets' ? 'active' : ''}`} onClick={() => { onSection('assets'); onFilter('all') }}>
          <BoxesIcon /><span>资产</span>
        </button>}
        <button aria-label="入离职交接" title={collapsed ? '入离职交接' : undefined} className={`nav-item ${section === 'handover' ? 'active' : ''}`} onClick={() => onSection('handover')}>
          <ClipboardIcon /><span>入离职交接</span>
        </button>
        <button aria-label="领用归还" title={collapsed ? '领用归还' : undefined} className={`nav-item ${section === 'operations' ? 'active' : ''}`} onClick={() => onSection('operations')}><ClipboardIcon /><span>领用归还</span></button>
        <button aria-label="资产盘点" title={collapsed ? '资产盘点' : undefined} className={`nav-item ${section === 'inventory' ? 'active' : ''}`} onClick={() => onSection('inventory')}><BoxesIcon /><span>资产盘点</span></button>
        <button aria-label="报修维修" title={collapsed ? '报修维修' : undefined} className={`nav-item ${section === 'repair' ? 'active' : ''}`} onClick={() => onSection('repair')}><ClipboardIcon /><span>报修维修</span></button>
        <button aria-label="耗材管理" title={collapsed ? '耗材管理' : undefined} className={`nav-item ${section === 'consumables' ? 'active' : ''}`} onClick={() => onSection('consumables')}><BoxesIcon /><span>耗材管理</span></button>
        <button aria-label="采购补货" title={collapsed ? '采购补货' : undefined} className={`nav-item ${section === 'procurement' ? 'active' : ''}`} onClick={() => onSection('procurement')}><BoxesIcon /><span>采购补货</span></button>
        {(dingtalkLogin || canManageUsers || permissions.includes('ASSET_EDIT') && permissions.includes('ASSET_CHECKOUT') && permissions.includes('ASSET_RETURN')) && <>{([['kits', '岗位配发模板'], ['support', '保修与供应商'], ['reports', '管理统计']] as const).map(([key, label]) => <button key={key} aria-label={label} title={collapsed ? label : undefined} className={`nav-item ${section === key ? 'active' : ''}`} onClick={() => onSection(key)}><ClipboardIcon /><span>{label}</span></button>)}</>}
        {(dingtalkLogin || canManageUsers) && <button aria-label="用户" title={collapsed ? '用户' : undefined} className={`nav-item ${section === 'users' ? 'active' : ''}`} onClick={() => onSection('users')}>
          <UsersIcon /><span>用户</span>
        </button>}
        {(dingtalkLogin || canManageUsers) && <button aria-label="日志" title={collapsed ? '日志' : undefined} className={`nav-item ${section === 'logs' ? 'active' : ''}`} onClick={() => onSection('logs')}>
          <ClipboardIcon /><span>日志</span>
        </button>}
        {canManageUsers && <button aria-label="设置" title={collapsed ? '设置' : undefined} className={`nav-item ${section === 'settings' ? 'active' : ''}`} onClick={() => onSection('settings')}>
          <SettingsIcon /><span>设置</span>
        </button>}
        {(dingtalkLogin || permissions.includes('ASSET_IMPORT') || permissions.includes('ASSET_EXPORT')) && <button aria-label="数据" title={collapsed ? '数据' : undefined} className={`nav-item ${section === 'transfer' ? 'active' : ''}`} onClick={() => onSection('transfer')}>
          <UploadIcon /><span>数据</span>
        </button>}
      </nav>
    </aside>
  )
}
