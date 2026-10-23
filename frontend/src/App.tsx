import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError, api } from './api'
import type { Assignee, DashboardOverview, Asset, AssetFilter, AssetPayload, AssetProfile, AuthUser, LookupType, LookupValue, Permission, Summary } from './types'
import { AssetDetail } from './components/AssetDetail'
import { AssetForm } from './components/AssetForm'
import { AssetTable } from './components/AssetTable'
import { MoonIcon, SunIcon } from './components/Icons'
import { LookupModal } from './components/LookupModal'
import { ArchiveIcon, BoxesIcon, CheckIcon, ChevronIcon, CloseIcon, LogoutIcon, PlusIcon, ReturnIcon, SearchIcon, UserIcon, WrenchIcon } from './components/Icons'
import { Sidebar } from './components/Sidebar'
import type { AppSection } from './components/Sidebar'
import { LoginPage } from './components/LoginPage'
import { UserManagement } from './components/UserManagement'
import { SystemSettings } from './components/SystemSettings'
import { ConfirmDialog } from './components/ConfirmDialog'
import { AuditLogPage } from './components/AuditLogPage'
import { AvatarEditor } from './components/AvatarEditor'
import { AssetLabelPrintPreview } from './components/AssetLabelPrintPreview'
import { ComputerDashboard } from './components/ComputerDashboard'
import { AssetTransfer } from './components/AssetTransfer'
import { HandoverPage } from './components/HandoverPage'
import { SelfServicePage } from './components/SelfServicePage'
import { OperationPage, canManageWorkflows } from './components/OperationPage'
import { InventoryPage } from './components/InventoryPage'
import { ConsumablePage } from './components/ConsumablePage'
import { WorkbenchPage } from './components/WorkbenchPage'
import { EquipmentKitPage } from './components/EquipmentKitPage'
import { AssetSupportPage } from './components/AssetSupportPage'
import { ManagementReportPage } from './components/ManagementReportPage'
import { ProcurementPage } from './components/ProcurementPage'
import { RegistrationPage } from './components/RegistrationPage'
import { DingTalkOaPage } from './components/DingTalkOaPage'

type Page = { name: 'list'; taskId?: number; review?: boolean; oa?: boolean } | { name: 'form'; asset: Asset | null; clone?: boolean } | { name: 'detail'; asset: Asset }
type AppHistoryState = { assetManagement: true; section: AppSection; page: Page; depth: number }
type LookupDeleteRequest = { lookup: LookupValue; resolve: (deleted: boolean) => void }

const filterLabels: Record<AssetFilter, string> = {
  all: '全部', available: '当前可用', checkedOut: '已经领出', maintenance: '维护中', scrapped: '已报废',
}
const isLabelPrintPage = window.location.pathname.replace(/\/+$/, '') === '/label-print'
const isMaintenance = (asset: Asset) => asset.status.name.includes('维修') || asset.status.name.includes('维护')
const isScrapped = (asset: Asset) => asset.status.name.includes('报废')
export default function App() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() => {
    try { return localStorage.getItem('asset-management-theme') === 'dark' ? 'dark' : 'light' } catch { return 'light' }
  })
  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try { localStorage.setItem('asset-management-theme', theme) } catch { /* Theme still works when storage is unavailable. */ }
  }, [theme])
  const [authUser, setAuthUser] = useState<AuthUser | null>(null)
  const [authChecking, setAuthChecking] = useState(true)
  const [loginLoading, setLoginLoading] = useState(false)
  const [loginError, setLoginError] = useState('')
  const [section, setSection] = useState<AppSection>('dashboard')
  const [page, setPage] = useState<Page>({ name: 'list' })
  const [assets, setAssets] = useState<Asset[]>([])
  const [suggestionAssets, setSuggestionAssets] = useState<Asset[]>([])
  const [overview, setOverview] = useState<DashboardOverview | null>(null)
  const [assignees, setAssignees] = useState<Assignee[]>([])
  const [lookups, setLookups] = useState<LookupValue[]>([])
  const [summary, setSummary] = useState<Summary>({ total: 0, available: 0, checkedOut: 0, maintenance: 0, scrapped: 0 })
  const [filter, setFilter] = useState<AssetFilter>('all')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [computerOnly, setComputerOnly] = useState(false)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [lookupType, setLookupType] = useState<LookupType | null>(null)
  const [lookupLoading, setLookupLoading] = useState(false)
  const [createdLookup, setCreatedLookup] = useState<LookupValue | null>(null)
  const [returnTarget, setReturnTarget] = useState<Asset | null>(null)
  const [returningId, setReturningId] = useState<number | null>(null)
  const [lookupDeleteRequest, setLookupDeleteRequest] = useState<LookupDeleteRequest | null>(null)
  const [lookupDeleting, setLookupDeleting] = useState(false)
  const [assetDeleteTarget, setAssetDeleteTarget] = useState<Asset | null>(null)
  const [assetDeleting, setAssetDeleting] = useState(false)
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const [avatarEditorOpen, setAvatarEditorOpen] = useState(false)
  const [avatarSaving, setAvatarSaving] = useState(false)
  const [avatarRevision, setAvatarRevision] = useState(() => Date.now())
  const [assetLoadError, setAssetLoadError] = useState('')
  const navigationRevision = useRef(0)
  const dataRevision = useRef(0)
  const accessKey = authUser ? `${authUser.id}:${authUser.dataScope}:${[...authUser.permissions].sort().join(',')}:${[...authUser.departments].sort().join(',')}` : ''

  useEffect(() => {
    setAssets([]); setSuggestionAssets([]); setPage({ name: 'list' })
  }, [accessKey])

  const showView = (nextSection: AppSection, nextPage: Page, mode: 'push' | 'replace' = 'push') => {
    navigationRevision.current++
    setCreatedLookup(null)
    setSection(nextSection)
    setPage(nextPage)
    const current = window.history.state as AppHistoryState | null
    const state: AppHistoryState = {
      assetManagement: true,
      section: nextSection,
      page: nextPage,
      depth: mode === 'push' ? (current?.assetManagement ? current.depth + 1 : 1) : (current?.depth ?? 0),
    }
    window.history[mode === 'push' ? 'pushState' : 'replaceState'](state, '', window.location.href)
  }

  const returnToPreviousView = () => {
    const current = window.history.state as AppHistoryState | null
    if (current?.assetManagement && current.depth > 0) window.history.back()
    else showView('assets', { name: 'list' }, 'replace')
  }

  const notify = (text: string, kind: 'ok' | 'error' = 'ok') => {
    setToast({ text, kind })
    window.setTimeout(() => setToast(null), 3200)
  }

  const load = useCallback(async (term = search) => {
    const revision = ++dataRevision.current
    setLoading(true); setAssetLoadError('')
    try {
      const canView = authUser?.permissions.includes('ASSET_VIEW')
      const assetRequest = canView ? api.listAssets(term) : Promise.resolve([])
      const suggestionRequest = canView && term.trim() ? api.listAssets('') : assetRequest
      const [assetData, lookupData, summaryData, suggestionData, dashboardData, assigneeData] = await Promise.all([
        assetRequest, canView || authUser?.canManageUsers ? api.listLookups() : Promise.resolve([]),
        canView ? api.summary() : Promise.resolve({ total: 0, available: 0, checkedOut: 0, maintenance: 0, scrapped: 0 }),
        suggestionRequest, api.dashboard(), authUser?.permissions.includes('ASSET_CHECKOUT') ? api.assignees() : Promise.resolve([]),
      ])
      if (revision !== dataRevision.current) return
      setAssets(assetData); setSuggestionAssets(suggestionData); setLookups(lookupData); setSummary(summaryData)
      setOverview(dashboardData); setAssignees(assigneeData)
    } catch (error) {
      if (revision !== dataRevision.current) return
      setAssets([]); setSuggestionAssets([])
      setAssetLoadError(error instanceof Error ? error.message : '读取数据失败')
      notify(error instanceof Error ? error.message : '读取数据失败', 'error')
      if (error instanceof ApiError && [401, 403].includes(error.status)) {
        setPage({ name: 'list' }); setSection('dashboard')
        void api.me().then(setAuthUser).catch(() => setAuthUser(null))
      }
    }
    finally { if (revision === dataRevision.current) setLoading(false) }
  }, [search, authUser])

  useEffect(() => {
    const url = new URL(window.location.href)
    const dingtalkError = url.searchParams.get('dingtalkError')
    const bound = url.searchParams.get('dingtalk') === 'bound'
    if (dingtalkError || bound) {
      if (dingtalkError) { setLoginError(dingtalkError); notify(dingtalkError, 'error') }
      else notify('钉钉绑定成功，下次可直接使用钉钉登录')
      url.searchParams.delete('dingtalkError'); url.searchParams.delete('dingtalk')
      window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
    }
    api.me().then(setAuthUser).catch((error) => {
      if (!(error instanceof ApiError) || error.status !== 401) setLoginError(error instanceof Error ? error.message : '无法连接服务器')
    }).finally(() => setAuthChecking(false))
  }, [])

  useEffect(() => {
    if (!isLabelPrintPage && authUser) void load('')
    return () => { dataRevision.current++ }
  }, [authUser])

  useEffect(() => {
    if (!authUser) return
    const refresh = () => { void api.me().then(user => setAuthUser(current => JSON.stringify(current) === JSON.stringify(user) ? current : user)).catch(error => {
      if (error instanceof ApiError && error.status === 401) { setAuthUser(null); setAssets([]); setSuggestionAssets([]) }
    }) }
    window.addEventListener('focus', refresh)
    return () => window.removeEventListener('focus', refresh)
  }, [authUser?.id])

  useEffect(() => {
    if (!authUser || loading) return
    const params = new URLSearchParams(window.location.search)
    const requestedQrToken = params.get('qr')
    const requestedAssetNumber = params.get('asset')
    if (!requestedQrToken && !requestedAssetNumber) return
    const requestedAsset = assets.find((asset) => requestedQrToken ? asset.qrToken === requestedQrToken : asset.assetTag === requestedAssetNumber)
    params.delete('qr')
    params.delete('asset')
    const cleanUrl = `${window.location.pathname}${params.size ? `?${params.toString()}` : ''}${window.location.hash}`
    if (!requestedAsset) {
      setToast({ kind: 'error', text: requestedAssetNumber ? `未找到资产编号 ${requestedAssetNumber}` : '二维码对应的资产不存在或已被删除' })
      window.history.replaceState({ assetManagement: true, section: 'assets', page: { name: 'list' }, depth: 0 } satisfies AppHistoryState, '', cleanUrl)
      return
    }
    const detailPage: Page = { name: 'detail', asset: requestedAsset }
    setSection('assets')
    setPage(detailPage)
    window.history.replaceState({ assetManagement: true, section: 'assets', page: detailPage, depth: 0 } satisfies AppHistoryState, '', cleanUrl)
  }, [authUser, assets, loading])

  useEffect(() => {
    if (!authUser) return
    const restoreView = async (state: AppHistoryState | null) => {
      if (!state?.assetManagement) return
      const revision = ++navigationRevision.current
      setCreatedLookup(null)
      setSection(state.section)
      if (state.page.name === 'list' || !state.page.asset) { setPage(state.page); return }
      setPage({ name: 'list' })
      try {
        const asset = await api.getAsset(state.page.asset.id)
        if (revision !== navigationRevision.current) return
        const freshPage: Page = { ...state.page, asset }
        setPage(freshPage)
        window.history.replaceState({ ...state, page: freshPage }, '', window.location.href)
      } catch (error) {
        if (revision !== navigationRevision.current) return
        notify(error instanceof Error ? error.message : '无法读取最新资产', 'error')
        window.history.replaceState({ ...state, page: { name: 'list' } }, '', window.location.href)
      }
    }
    const current = window.history.state as AppHistoryState | null
    if (current?.assetManagement) {
      void restoreView(current)
    } else {
      window.history.replaceState({ assetManagement: true, section: 'dashboard', page: { name: 'list' }, depth: 0 } satisfies AppHistoryState, '', window.location.href)
    }
    const onPopState = (event: PopStateEvent) => { void restoreView(event.state as AppHistoryState | null) }
    window.addEventListener('popstate', onPopState)
    return () => { navigationRevision.current++; window.removeEventListener('popstate', onPopState) }
  }, [authUser?.id])

  useEffect(() => {
    window.scrollTo(0, 0)
  }, [page.name, page.name === 'list' ? 0 : page.asset?.id ?? 0])

  const hasPermission = (permission: Permission) => Boolean(authUser?.permissions.includes(permission))

  const login = async (username: string, password: string) => {
    setLoginLoading(true); setLoginError('')
    try { setAuthUser(await api.login(username, password)) }
    catch (error) { setLoginError(error instanceof Error ? error.message : '登录失败') }
    finally { setLoginLoading(false) }
  }

  const logout = async () => {
    try { await api.logout() } catch { /* Session may already be invalid; still return to login. */ }
    setAuthUser(null); setSection('dashboard'); setPage({ name: 'list' }); setAssets([]); setSuggestionAssets([])
    window.history.replaceState(null, '', window.location.href)
  }

  const uploadAvatar = async (file: File) => {
    if (!authUser) return
    setAvatarSaving(true)
    try {
      const result = await api.uploadMyAvatar(file)
      setAuthUser((current) => current ? { ...current, hasAvatar: result.hasAvatar } : current)
      setAvatarRevision(Date.now())
      setAvatarEditorOpen(false)
      notify('头像已更新')
    } catch (error) { notify(error instanceof Error ? error.message : '头像保存失败', 'error') }
    finally { setAvatarSaving(false) }
  }

  const deleteMyAvatar = async () => {
    if (!authUser) return
    setAvatarSaving(true)
    try {
      const result = await api.deleteMyAvatar()
      setAuthUser((current) => current ? { ...current, hasAvatar: result.hasAvatar } : current)
      setAvatarRevision(Date.now())
      setAvatarEditorOpen(false)
      notify('头像已删除')
    } catch (error) { notify(error instanceof Error ? error.message : '头像删除失败', 'error') }
    finally { setAvatarSaving(false) }
  }

  const categoryOptions = useMemo(() => {
    const categories = new Map<number, LookupValue>()
    lookups.filter((lookup) => lookup.type === 'CATEGORY').forEach((lookup) => categories.set(lookup.id, lookup))
    assets.forEach((asset) => categories.set(asset.category.id, asset.category))
    return Array.from(categories.values()).sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
  }, [assets, lookups])
  const tableParameterProfile: AssetProfile | null = computerOnly
    ? 'COMPUTER'
    : categoryFilter
      ? categoryOptions.find((category) => String(category.id) === categoryFilter)?.assetProfile || 'GENERAL'
      : null

  const visibleAssets = useMemo(() => assets.filter((asset) => {
    if (computerOnly && asset.category.assetProfile !== 'COMPUTER') return false
    if (categoryFilter && String(asset.category.id) !== categoryFilter) return false
    if (filter === 'available') return !asset.checkedOut && !isMaintenance(asset) && !isScrapped(asset)
    if (filter === 'checkedOut') return asset.checkedOut && !isScrapped(asset)
    if (filter === 'maintenance') return isMaintenance(asset)
    if (filter === 'scrapped') return isScrapped(asset)
    return true
  }), [assets, categoryFilter, filter, computerOnly])
  const saveAsset = async (payload: AssetPayload) => {
    setSaving(true)
    try {
      const editingAsset = page.name === 'form' && !page.clone ? page.asset : null
      if (editingAsset) await api.updateAsset(editingAsset.id, payload)
      else await api.createAsset(payload, page.name === 'form' && page.clone ? page.asset?.id : undefined)
      notify(editingAsset ? '资产已更新' : page.name === 'form' && page.clone ? '克隆资产已创建' : '资产已创建')
      showView('assets', { name: 'list' }, 'replace'); await load('')
    } catch (error) { notify(error instanceof Error ? error.message : '保存失败', 'error') }
    finally { setSaving(false) }
  }

  const createLookup = async (name: string, assetProfile?: AssetProfile) => {
    if (!lookupType) return
    setLookupLoading(true)
    try {
      const created = await api.createLookup(lookupType, name, assetProfile)
      setLookups((current) => [...current, created]); setCreatedLookup(created)
      notify(`${created.typeLabel}“${created.name}”已创建`); setLookupType(null)
    } catch (error) { notify(error instanceof Error ? error.message : '创建选项失败', 'error') }
    finally { setLookupLoading(false) }
  }

  const deleteLookup = (lookup: LookupValue) => new Promise<boolean>((resolve) => {
    setLookupDeleteRequest({ lookup, resolve })
  })

  const cancelLookupDelete = () => {
    if (lookupDeleting || !lookupDeleteRequest) return
    lookupDeleteRequest.resolve(false)
    setLookupDeleteRequest(null)
  }

  const confirmLookupDelete = async () => {
    if (!lookupDeleteRequest) return
    const { lookup, resolve } = lookupDeleteRequest
    setLookupDeleting(true)
    try {
      await api.deleteLookup(lookup.id)
      setLookups((current) => current.filter((item) => item.id !== lookup.id))
      notify(`${lookup.typeLabel}“${lookup.name}”已删除`)
      resolve(true)
      setLookupDeleteRequest(null)
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除选项失败', 'error')
      resolve(false)
      setLookupDeleteRequest(null)
    }
    finally { setLookupDeleting(false) }
  }

  const deleteAsset = (asset: Asset) => setAssetDeleteTarget(asset)

  const confirmAssetDelete = async () => {
    if (!assetDeleteTarget) return
    setAssetDeleting(true)
    try { await api.deleteAsset(assetDeleteTarget.id); notify('资产已删除'); setAssetDeleteTarget(null); showView('assets', { name: 'list' }, 'replace'); await load(search) }
    catch (error) { notify(error instanceof Error ? error.message : '删除失败', 'error') }
    finally { setAssetDeleting(false) }
  }

  const returnAsset = async () => {
    if (!returnTarget) return
    setReturningId(returnTarget.id)
    try {
      const updated = await api.returnAsset(returnTarget.id, returnTarget.version)
      notify(`${returnTarget.assetTag} 已归还，领用人已清空，状态：${updated.status.name}`)
      setReturnTarget(null); showView('assets', { name: 'detail', asset: updated }, 'replace'); await load(search)
    } catch (error) { notify(error instanceof Error ? error.message : '归还失败', 'error') }
    finally { setReturningId(null) }
  }

  if (authChecking) return <div className="auth-loading"><span className="brand-mark"><BoxesIcon /></span><span className="spinner" />正在验证登录状态…</div>
  if (!authUser) return <LoginPage loading={loginLoading} error={loginError} onLogin={login} />
  if (isLabelPrintPage) return <AssetLabelPrintPreview />

  const metrics: Array<{ filter: AssetFilter; label: string; value: number; icon: typeof CheckIcon; tone: string }> = [
    { filter: 'all', label: '全部资产', value: summary.total, icon: BoxesIcon, tone: 'indigo' },
    { filter: 'available', label: '当前可用', value: summary.available, icon: CheckIcon, tone: 'mint' },
    { filter: 'checkedOut', label: '已经领出', value: summary.checkedOut, icon: UserIcon, tone: 'peach' },
    { filter: 'maintenance', label: '维护中', value: summary.maintenance, icon: WrenchIcon, tone: 'violet' },
    { filter: 'scrapped', label: '已报废', value: summary.scrapped, icon: ArchiveIcon, tone: 'slate' },
  ]

  const accountName = authUser.displayName || authUser.username
  const topbar = <>
    <header className="topbar">
      <div className="topbar-actions"><button type="button" className="theme-toggle" aria-label={theme === 'light' ? '切换为暗色' : '切换为亮色'} title={theme === 'light' ? '切换为暗色' : '切换为亮色'} onClick={() => setTheme((current) => current === 'light' ? 'dark' : 'light')}>{theme === 'light' ? <MoonIcon /> : <SunIcon />}</button><button className="avatar avatar-button" aria-label="设置我的头像" title="设置我的头像" onClick={() => setAvatarEditorOpen(true)}>{authUser.hasAvatar ? <img src={`/api/users/${authUser.id}/avatar?v=${avatarRevision}`} alt="我的头像" /> : accountName.slice(0, 1).toUpperCase()}</button><div className="account-summary"><strong>{accountName}</strong></div><button className="logout-button" aria-label="退出登录" title="退出登录" onClick={() => void logout()}><LogoutIcon /></button></div>
    </header>
    {avatarEditorOpen && <AvatarEditor user={authUser} revision={avatarRevision} busy={avatarSaving} onClose={() => !avatarSaving && setAvatarEditorOpen(false)} onUpload={uploadAvatar} onDelete={deleteMyAvatar} onError={(message) => notify(message, 'error')} />}
  </>

  const restrictedSection = (section === 'users' || section === 'logs' || section === 'settings') && !authUser.canManageUsers
    ? { users: '用户', logs: '日志', settings: '设置' }[section]
    : section === 'transfer' && !(hasPermission('ASSET_EXPORT') || (hasPermission('ASSET_IMPORT') && hasPermission('ASSET_CREATE') && hasPermission('ASSET_CHECKOUT') && authUser.dataScope === 'ALL'))
      ? '数据'
      : section === 'assets' && !hasPermission('ASSET_VIEW') ? '资产' : null

  if (restrictedSection) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><section className="transfer-page"><div className="transfer-card"><div><h2>{restrictedSection}</h2><p>当前账号尚未获得此功能的访问权限，请联系管理员授权。</p></div></div></section></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  const taskId = page.name === 'list' && Number.isSafeInteger(page.taskId) && (page.taskId ?? 0) > 0 ? page.taskId : undefined
  const openOa = () => showView('handover', { name: 'list', oa: true })
  if (section === 'handover' && page.name === 'list' && page.oa) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><DingTalkOaPage key={accessKey} user={authUser} onBack={() => showView('handover', { name: 'list' })} onNotify={notify} onAssetsChanged={() => void load('')} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>
  if (section === 'workbench' || section === 'kits' || section === 'support' || section === 'reports' || section === 'procurement' || section === 'registrations') return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content">{section === 'workbench' ? <WorkbenchPage key={accessKey} onOpen={(next, id, state) => showView(next, { name: 'list', taskId: id || undefined, review: next === 'registrations' && state !== 'NEEDS_INFO' && authUser.dataScope === 'ALL' && ['ASSET_VIEW', 'ASSET_CREATE', 'ASSET_CHECKOUT'].every(value => authUser.permissions.includes(value as Permission)) })} />
      : section === 'kits' ? <EquipmentKitPage key={accessKey} user={authUser} onNotify={notify} />
        : section === 'support' ? <AssetSupportPage key={`${accessKey}:${taskId || ''}`} user={authUser} assets={suggestionAssets} initialId={taskId} onNotify={notify} />
          : section === 'reports' ? <ManagementReportPage key={accessKey} user={authUser} />
            : section === 'procurement' ? <ProcurementPage key={`${accessKey}:${taskId || ''}`} user={authUser} assets={suggestionAssets} initialId={taskId} onNotify={notify} onAssetsChanged={() => void load('')} />
              : <RegistrationPage key={`${accessKey}:${taskId || ''}`} user={authUser} assets={suggestionAssets} lookups={lookups} review={page.name === 'list' && page.review} initialId={taskId} onNotify={notify} onAssetsChanged={() => void load('')} />}</div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'selfService') return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><SelfServicePage key={accessKey} user={authUser} assets={suggestionAssets} lookups={lookups} loading={loading} error={assetLoadError} onRefresh={() => void load('')} onSelect={(asset) => showView('assets', { name: 'detail', asset })} onNotify={notify} onOpenOa={openOa} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'handover') return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><HandoverPage key={`${accessKey}:${taskId || ''}`} initialId={taskId} canUseKits={canManageWorkflows(authUser)} currentUserId={authUser.id} assets={suggestionAssets} onNotify={notify} onOpenOa={openOa} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'operations' || section === 'repair' || section === 'inventory' || section === 'consumables') return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content">{section === 'inventory'
      ? <InventoryPage key={`${accessKey}:inventory:${taskId || ''}`} initialId={taskId} user={authUser} onNotify={notify} />
      : section === 'consumables' ? <ConsumablePage key={`${accessKey}:consumables:${taskId || ''}`} initialId={taskId} user={authUser} onNotify={notify} />
        : <OperationPage key={`${accessKey}:${section}:${taskId || ''}`} initialId={taskId} user={authUser} assets={suggestionAssets} repair={section === 'repair'} onNotify={notify} onAssetsChanged={() => void load('')} onOpenOa={openOa} />}</div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'users' && authUser.canManageUsers) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><UserManagement currentUserId={authUser.id} avatarRevision={avatarRevision} onNotify={notify} onPasswordChanged={() => void logout()} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'settings' && authUser.canManageUsers) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><SystemSettings onNotify={notify} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'transfer' && (hasPermission('ASSET_IMPORT') || hasPermission('ASSET_EXPORT'))) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><AssetTransfer canExport={hasPermission('ASSET_EXPORT')} canImport={hasPermission('ASSET_IMPORT') && hasPermission('ASSET_CREATE') && hasPermission('ASSET_CHECKOUT') && authUser.dataScope === 'ALL'} onNotify={notify} onImported={() => load(search)} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'logs' && authUser.canManageUsers) return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content"><AuditLogPage onNotify={notify} /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  if (section === 'dashboard' || !hasPermission('ASSET_VIEW') || section !== 'assets') return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content cockpit-content"><ComputerDashboard
      assets={suggestionAssets}
      overview={overview}
      canView={hasPermission('ASSET_VIEW')}
      loading={loading}
      canCreate={hasPermission('ASSET_CREATE')}
      onRefresh={() => void load('')}
      onCreate={() => showView('assets', { name: 'form', asset: null })}
      onViewAssets={(nextFilter) => { setAssets(suggestionAssets); setFilter(nextFilter); setCategoryFilter(''); setComputerOnly(true); setSearch(''); showView('assets', { name: 'list' }) }}
      onSelect={(asset) => showView('assets', { name: 'detail', asset })}
    /></div></main>
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>

  const listPage = <section className="invoice-list-page">
    <div className="invoice-list-outer"><section className="invoice-list-card">
      <div className="invoice-metrics">{metrics.map((metric) => {
        const MetricIcon = metric.icon
        return <button key={metric.filter} className={`invoice-metric ${metric.tone} ${filter === metric.filter ? 'selected' : ''}`} onClick={() => setFilter(metric.filter)}><span><MetricIcon /></span><div><strong>{metric.label}</strong><small>{metric.value} 项资产</small></div></button>
      })}</div>
      <div className="invoice-toolbar">
        <div className="invoice-toolbar-filters">
          <label className="category-filter" title="按资产分类筛选">
            <select aria-label="筛选资产分类" value={computerOnly ? '__computers' : categoryFilter} onChange={(event) => { setComputerOnly(event.target.value === '__computers'); setCategoryFilter(event.target.value === '__computers' ? '' : event.target.value) }}>
              <option value="">全部分类</option>
              <option value="__computers">全部电脑</option>
              {categoryOptions.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}
            </select>
            <span className="category-filter-chevron"><ChevronIcon /></span>
          </label>
          <form className="asset-search" role="search" aria-label="搜索资产" onSubmit={(event) => { event.preventDefault(); if (!loading) void load(search) }}>
            <div className="search-box">
              <input aria-label="搜索资产" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索资产" />
            </div>
            <button className="button primary asset-search-submit" type="submit" disabled={loading} aria-busy={loading}>
              <span className="asset-search-submit-icon" aria-hidden="true">{loading ? <span className="asset-search-loading-dot" /> : <SearchIcon />}</span>
              <span className="asset-search-submit-label">{loading ? '搜索中…' : '搜索'}</span>
            </button>
          </form>
        </div>
        {hasPermission('ASSET_CREATE') && <button className="button primary" onClick={() => showView('assets', { name: 'form', asset: null })}><PlusIcon />新增资产</button>}
      </div>
      <AssetTable assets={visibleAssets} loading={loading} canEdit={hasPermission('ASSET_EDIT')} canCreate={hasPermission('ASSET_CREATE')} parameterProfile={tableParameterProfile} onSelect={(asset) => showView('assets', { name: 'detail', asset })} onEdit={(asset) => showView('assets', { name: 'form', asset })} onClone={(asset) => showView('assets', { name: 'form', asset, clone: true })} />
      <div className="panel-foot"><span>共 {visibleAssets.length} 条{categoryFilter ? categoryOptions.find((category) => String(category.id) === categoryFilter)?.name ?? '分类资产' : filter === 'all' ? '资产' : filterLabels[filter]}</span><span>点击资产行查看完整档案</span></div>
    </section></div>
  </section>

  const assetPage = page.name === 'form' && (page.clone || !page.asset ? hasPermission('ASSET_CREATE') : hasPermission('ASSET_EDIT'))
    ? <AssetForm key={`${page.clone ? 'clone' : 'form'}-${page.asset?.id ?? 'new'}`} asset={page.asset} clone={page.clone} lookups={lookups} assignees={assignees} canAssign={hasPermission('ASSET_CHECKOUT')} bindableAssets={suggestionAssets} saving={saving} createdLookup={createdLookup} onCancel={returnToPreviousView} onSave={saveAsset} onDeleteLookup={deleteLookup} onNewLookup={(type) => { setCreatedLookup(null); setLookupType(type) }} />
    : page.name === 'detail'
      ? <AssetDetail asset={page.asset} canEdit={hasPermission('ASSET_EDIT')} canCreate={hasPermission('ASSET_CREATE')} canReturn={hasPermission('ASSET_RETURN')} canDelete={hasPermission('ASSET_DELETE')} onClose={returnToPreviousView} onEdit={(asset) => showView('assets', { name: 'form', asset })} onClone={(asset) => showView('assets', { name: 'form', asset, clone: true })} onReturn={setReturnTarget} onDelete={(asset) => void deleteAsset(asset)} />
      : listPage

  return <div className="app-shell">
    <Sidebar section={section} canManageUsers={authUser.canManageUsers} dingtalkLogin={authUser.dingtalkLogin} permissions={authUser.permissions} onFilter={setFilter} onSection={(next) => showView(next, { name: 'list' })} />
    <main className="main-content">{topbar}<div className="content modern-content">{assetPage}</div></main>
    <LookupModal type={lookupType} loading={lookupLoading} onClose={() => setLookupType(null)} onCreate={createLookup} />
    {returnTarget && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setReturnTarget(null)}><div className="return-card">
      <div className="return-icon"><ReturnIcon /></div><button className="icon-button modal-close" onClick={() => setReturnTarget(null)}><CloseIcon /></button>
      <span className="eyebrow">RETURN ASSET</span><h3>确认归还这项资产？</h3><p>归还后清空领用人；维修、维护和报废状态保留，其他资产恢复为当前可用。CPU、价格等其他档案不会改变。</p>
      <div className="return-asset"><BoxesIcon /><div><strong>{returnTarget.name}</strong><span>{returnTarget.assetTag} · 当前领用人：{returnTarget.assignedTo || '—'}</span></div></div>
      <div className="modal-actions"><button className="button ghost" onClick={() => setReturnTarget(null)}>取消</button><button className="button primary" disabled={returningId === returnTarget.id} onClick={() => void returnAsset()}><ReturnIcon />{returningId === returnTarget.id ? '归还中…' : '确认归还'}</button></div>
    </div></div>}
    {lookupDeleteRequest && <ConfirmDialog
      title={`删除${lookupDeleteRequest.lookup.typeLabel}`}
      description={lookupDeleteRequest.lookup.type === 'CPU'
        ? `确认删除 CPU 候选项“${lookupDeleteRequest.lookup.name}”吗？它会从下拉框移除；已经保存的其他资产资料不会被批量修改。当前编辑框若正使用此值，将同步清空，保存后生效。`
        : `确认删除“${lookupDeleteRequest.lookup.name}”吗？只有未被资产使用的选项才能删除；如正在使用，系统会阻止删除，现有资产资料不会改变。`}
      busy={lookupDeleting}
      onCancel={cancelLookupDelete}
      onConfirm={() => void confirmLookupDelete()}
    />}
    {assetDeleteTarget && <ConfirmDialog
      title="删除资产"
      description={`确认永久删除“${assetDeleteTarget.name}”（${assetDeleteTarget.assetTag}）吗？设备绑定关系、随附配件和资产图片也会一起删除，且无法恢复。`}
      busy={assetDeleting}
      onCancel={() => !assetDeleting && setAssetDeleteTarget(null)}
      onConfirm={() => void confirmAssetDelete()}
    />}
    {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
  </div>
}
