import { useState } from 'react'
import type { AssetFilter, Permission } from '../types'
const version = 'v26.915.1651'
import { BoxesIcon, ClipboardIcon, DashboardIcon, SettingsIcon, SidebarPanelIcon, UploadIcon, UsersIcon } from './Icons'

export type AppSection = 'dashboard' | 'assets' | 'transfer' | 'users' | 'logs' | 'settings'

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
        {(dingtalkLogin || permissions.includes('ASSET_VIEW')) && <button aria-label="资产" title={collapsed ? '资产' : undefined} className={`nav-item ${section === 'assets' ? 'active' : ''}`} onClick={() => { onSection('assets'); onFilter('all') }}>
          <BoxesIcon /><span>资产</span>
        </button>}
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
