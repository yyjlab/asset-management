import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api'
import type { AccessPayload, Permission, CreateUserPayload, ManagedUser, UpdateUserPayload } from '../types'
import { CheckIcon, CloseIcon, PlusIcon, TrashIcon, UserIcon } from './Icons'
import { ConfirmDialog } from './ConfirmDialog'

const permissionLabels: Record<Permission, string> = {
  ASSET_VIEW: '查看资产', ASSET_CREATE: '新增资产', ASSET_EDIT: '编辑资产', ASSET_CHECKOUT: '分配领用人',
  ASSET_RETURN: '归还资产', ASSET_DELETE: '删除资产', ASSET_IMPORT: '导入资产', ASSET_EXPORT: '导出资产',
}
const scopeLabels = { SELF: '仅本人领用', DEPARTMENT: '指定部门', ALL: '全部资产' }

type Draft = { id?: number; username: string; password: string }
const freshDraft = (): Draft => ({ username: '', password: '' })

interface Props { currentUserId: number; avatarRevision: number; onNotify: (text: string, kind?: 'ok' | 'error') => void; onPasswordChanged: () => void }

export function UserManagement({ currentUserId, avatarRevision, onNotify, onPasswordChanged }: Props) {
  const [users, setUsers] = useState<ManagedUser[]>([])
  const [source, setSource] = useState<'all' | 'dingtalk' | 'local'>('all')
  const [accessUser, setAccessUser] = useState<ManagedUser | null>(null)
  const [access, setAccess] = useState<AccessPayload | null>(null)
  const [accessSaving, setAccessSaving] = useState(false)
  const [superAdminConfirmed, setSuperAdminConfirmed] = useState(false)
  const [departmentText, setDepartmentText] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [passwordEditor, setPasswordEditor] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [changingPassword, setChangingPassword] = useState(false)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<ManagedUser | null>(null)
  const [avatarDeleteTarget, setAvatarDeleteTarget] = useState<ManagedUser | null>(null)
  const [avatarDeletingId, setAvatarDeletingId] = useState<number | null>(null)

  useEffect(() => {
    if (!accessUser) return
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = previous }
  }, [accessUser])

  const load = async () => {
    setLoading(true)
    try { setUsers(await api.listUsers()) }
    catch (error) { onNotify(error instanceof Error ? error.message : '读取用户失败', 'error') }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [avatarRevision])

  const openEdit = (user: ManagedUser) => {
    if (user.role === 'SUPER_ADMIN') return
    setDraft({ id: user.id, username: user.username, password: '' })
  }
  const openPasswordEditor = () => {
    setDraft(null)
    setCurrentPassword('')
    setNewPassword('')
    setConfirmPassword('')
    setPasswordEditor(true)
  }
  const changeMyPassword = async () => {
    if (!currentPassword) { onNotify('请输入当前密码', 'error'); return }
    if (newPassword.length < 8) { onNotify('新密码至少需要8位', 'error'); return }
    if (newPassword !== confirmPassword) { onNotify('两次输入的新密码不一致', 'error'); return }
    setChangingPassword(true)
    try {
      await api.changeMyPassword(currentPassword, newPassword)
      setPasswordEditor(false)
      setCurrentPassword(''); setNewPassword(''); setConfirmPassword('')
      onNotify('密码已更新，请使用新密码重新登录')
      onPasswordChanged()
    } catch (error) { onNotify(error instanceof Error ? error.message : '修改密码失败', 'error') }
    finally { setChangingPassword(false) }
  }
  const save = async () => {
    if (!draft) return
    if (!draft.username.trim() || draft.password.length < 8) {
      onNotify('请填写登录账号和至少8位密码', 'error'); return
    }
    setSaving(true)
    try {
      if (draft.id) {
        const payload: UpdateUserPayload = { password: draft.password }
        await api.updateUser(draft.id, payload)
        onNotify('普通用户密码已重置')
      } else {
        const payload: CreateUserPayload = { username: draft.username, password: draft.password }
        await api.createUser(payload)
        onNotify('普通用户已创建')
      }
      setDraft(null); await load()
    } catch (error) { onNotify(error instanceof Error ? error.message : '保存用户失败', 'error') }
    finally { setSaving(false) }
  }

  const deleteUser = async (user: ManagedUser) => {
    if (user.role === 'SUPER_ADMIN') return
    setDeletingId(user.id)
    try {
      await api.deleteUser(user.id)
      onNotify(`用户“${user.username}”已删除`)
      setDeleteTarget(null)
      await load()
    } catch (error) { onNotify(error instanceof Error ? error.message : '删除用户失败', 'error') }
    finally { setDeletingId(null) }
  }

  const clearAvatar = async (user: ManagedUser) => {
    if (user.role === 'SUPER_ADMIN') return
    setAvatarDeletingId(user.id)
    try {
      await api.deleteUserAvatar(user.id)
      onNotify(`用户“${user.username}”的头像已清除`)
      setAvatarDeleteTarget(null)
      await load()
    } catch (error) { onNotify(error instanceof Error ? error.message : '清除头像失败', 'error') }
    finally { setAvatarDeletingId(null) }
  }

  const openAccess = (user: ManagedUser) => {
    if (user.protectedAccount || user.id === currentUserId) return
    setAccessUser(user)
    setAccess({ role: user.role, permissions: [...user.permissions], dataScope: user.dataScope, departments: [...user.departments], enabled: user.enabled })
    setSuperAdminConfirmed(false)
    setDepartmentText(user.departments.join('，'))
  }
  const saveAccess = async () => {
    if (!access || !accessUser) return
    const roleChanged = access.role !== accessUser.role
    if (roleChanged && access.role === 'SUPER_ADMIN' && !superAdminConfirmed) { onNotify('请先确认授予超级管理员权限', 'error'); return }
    const departments = access.dataScope === 'DEPARTMENT' ? departmentText.split(/[,，\n]/).map(value => value.trim()).filter(Boolean) : []
    if (access.dataScope === 'DEPARTMENT' && !departments.length) { onNotify('请填写允许访问的部门', 'error'); return }
    setAccessSaving(true)
    try {
      await api.updateUserAccess(accessUser.id, { ...access, departments })
      setAccessUser(null); setAccess(null); await load()
      onNotify(roleChanged ? '角色已更新，该账号需要重新登录' : '权限和数据范围已保存，下一次请求生效')
    } catch (error) { onNotify(error instanceof Error ? error.message : '权限保存失败', 'error') }
    finally { setAccessSaving(false) }
  }
  const togglePermission = (permission: Permission, checked: boolean) => {
    if (!access) return
    const values = new Set(access.permissions)
    if (checked) { values.add(permission); values.add('ASSET_VIEW'); if (permission === 'ASSET_IMPORT') { values.add('ASSET_CREATE'); values.add('ASSET_CHECKOUT') } }
    else {
      values.delete(permission)
      if (permission === 'ASSET_VIEW') values.clear()
      if (permission === 'ASSET_CREATE') values.delete('ASSET_IMPORT')
      if (permission === 'ASSET_CHECKOUT') values.delete('ASSET_IMPORT')
    }
    setAccess({ ...access, permissions: [...values] })
  }

  const filteredUsers = users.filter(user => source === 'all' || (source === 'dingtalk' ? user.dingtalkBound : !user.dingtalkBound))
  return <div className="user-page">
    <section className="user-panel">
      <div className="user-panel-head"><div><h2>账号列表</h2><span>{users.length} 个账号</span></div><button className="button primary user-create" onClick={() => setDraft(freshDraft())}><PlusIcon />创建普通用户</button></div>
      <div className="user-source-tabs" role="group" aria-label="账号来源筛选">{(['all', 'dingtalk', 'local'] as const).map(value => <button type="button" key={value} className={`button ${source === value ? 'primary' : 'ghost'}`} onClick={() => setSource(value)}>{value === 'all' ? '全部用户' : value === 'dingtalk' ? '钉钉用户' : '本地用户'}</button>)}</div>
      {loading ? <div className="user-loading"><span className="spinner" />正在读取用户…</div> : <div className="user-list">
        {!filteredUsers.length && <div className="user-loading">{source === 'dingtalk' ? '暂无钉钉用户，员工首次钉钉登录或绑定后会显示在这里。' : '暂无此类账号'}</div>}
        {filteredUsers.map((user) => <article key={user.id} className={`user-row ${!user.enabled ? 'disabled' : ''}`}>
          <div className="user-avatar">{user.hasAvatar ? <img src={`/api/users/${user.id}/avatar?v=${avatarRevision}-${encodeURIComponent(user.updatedAt)}`} alt={`${user.username}的头像`} /> : <UserIcon />}</div><div className="user-identity"><div><strong>{user.displayName || user.username}</strong><span className={user.role === 'SUPER_ADMIN' ? 'role super' : 'role'}>{user.role === 'SUPER_ADMIN' ? '超级管理员' : '普通用户'}</span>{!user.enabled && <span className="role off">已停用</span>}</div><small>{user.username} · {user.dingtalkBound ? '钉钉账号' : '本地账号'} · {scopeLabels[user.dataScope]}</small></div>
          <div className="user-updated"><span>最近更新</span><strong>{new Date(user.updatedAt).toLocaleString('zh-CN', { hour12: false })}</strong></div>
          <div className="user-actions">{user.role === 'SUPER_ADMIN'
            ? <>{user.id === currentUserId
              ? <button className="button ghost self-password-button" onClick={openPasswordEditor}>修改我的密码</button>
              : !user.protectedAccount && <button className="button ghost" onClick={() => openAccess(user)}>权限设置</button>}
              {user.protectedAccount && user.id !== currentUserId && <span className="role">原始管理员受保护</span>}</>
            : <><button className="button ghost" onClick={() => openAccess(user)}>权限设置</button>{user.hasAvatar && <button className="button ghost avatar-clear-button" disabled={avatarDeletingId === user.id} onClick={() => setAvatarDeleteTarget(user)}>{avatarDeletingId === user.id ? '清除中…' : '清除头像'}</button>}<button className="button ghost" onClick={() => openEdit(user)}>重置密码</button><button className="button user-delete-button" disabled={deletingId === user.id} onClick={() => setDeleteTarget(user)}><TrashIcon />{deletingId === user.id ? '删除中…' : '删除用户'}</button></>}
          </div>
        </article>)}
      </div>}
    </section>
    {draft && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setDraft(null)}><div className="user-editor">
      <button className="icon-button modal-close" onClick={() => setDraft(null)}><CloseIcon /></button><span className="eyebrow">{draft.id ? 'RESET PASSWORD' : 'NEW USER'}</span><h2>{draft.id ? '重置普通用户密码' : '创建普通用户'}</h2><p>{draft.id ? '超级管理员可以为该普通用户设置一个新的登录密码。' : '填写姓名拼音作为登录账号，再设置登录密码即可。'}</p>
      {draft.id ? <div className="reset-account"><div className="user-avatar"><UserIcon /></div><div><strong>{draft.username}</strong></div></div> : <div className="user-form-grid single-field"><label><span>登录账号（姓名拼音）</span><input autoFocus value={draft.username} onChange={(event) => setDraft({ ...draft, username: event.target.value })} placeholder="例如 zhangsan" /></label></div>}
      <div className="user-form-grid password-grid"><label className="full"><span>{draft.id ? '新登录密码' : '登录密码'}</span><input type="password" value={draft.password} onChange={(event) => setDraft({ ...draft, password: event.target.value })} placeholder="至少8位" /></label></div>
      {!draft.id && <div className="all-permissions"><CheckIcon /><div><strong>本地账号默认保留全部资产权限</strong><span>创建后可通过“权限设置”调整；钉钉首次登录账号默认仅查看本人资产。</span></div></div>}
      <div className="modal-actions"><button className="button ghost" onClick={() => setDraft(null)}>取消</button><button className="button primary" disabled={saving} onClick={() => void save()}>{saving ? '保存中…' : draft.id ? '确认重置' : '创建用户'}</button></div>
    </div></div>}
    {passwordEditor && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setPasswordEditor(false)}><div className="user-editor password-editor">
      <button className="icon-button modal-close" onClick={() => setPasswordEditor(false)}><CloseIcon /></button><span className="eyebrow">SECURITY</span><h2>修改我的密码</h2><p>修改的是数据库中现有超级管理员的登录密码，不需要重新构建镜像，也不会影响资产数据。</p>
      <div className="user-form-grid password-grid"><label className="full"><span>当前密码</span><input autoFocus autoComplete="current-password" type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} placeholder="请输入目前正在使用的密码" /></label><label className="full"><span>新密码</span><input autoComplete="new-password" type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} placeholder="8–80位，请勿与当前密码相同" /></label><label className="full"><span>再次输入新密码</span><input autoComplete="new-password" type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} placeholder="再次输入新密码" /></label></div>
      <div className="password-safety-note"><CheckIcon /><span>密码将使用 BCrypt 加密保存，页面和数据库都不会保存明文。</span></div>
      <div className="modal-actions"><button className="button ghost" onClick={() => setPasswordEditor(false)}>取消</button><button className="button primary" disabled={changingPassword} onClick={() => void changeMyPassword()}>{changingPassword ? '修改中…' : '确认修改'}</button></div>
    </div></div>}
    {accessUser && access && createPortal(<div className="modal-backdrop" onMouseDown={event => event.target === event.currentTarget && !accessSaving && setAccessUser(null)}><div className="user-editor access-editor" role="dialog" aria-modal="true" aria-labelledby="access-title">
      <button className="icon-button modal-close" aria-label="关闭权限设置" disabled={accessSaving} onClick={() => setAccessUser(null)}><CloseIcon /></button>
      <h2 id="access-title">权限设置</h2><p>{accessUser.displayName}（{accessUser.username}）· {accessUser.dingtalkBound ? '钉钉账号' : '本地账号'}</p>
      <div className="user-source-tabs"><button className="button ghost" disabled={accessSaving} onClick={() => setAccess({ ...access, role: 'USER', permissions: ['ASSET_VIEW'], dataScope: 'SELF', departments: [] })}>普通员工</button><button className="button ghost" disabled={accessSaving} onClick={() => setAccess({ ...access, role: 'USER', permissions: ['ASSET_VIEW'], dataScope: 'ALL', departments: [] })}>只读查看</button><button className="button ghost" disabled={accessSaving} onClick={() => setAccess({ ...access, role: 'USER', permissions: Object.keys(permissionLabels) as Permission[], dataScope: 'ALL', departments: [] })}>资产管理员</button><button className={`button ${access.role === 'SUPER_ADMIN' ? 'primary' : 'ghost'}`} disabled={accessSaving} onClick={() => setAccess({ ...access, role: 'SUPER_ADMIN', permissions: Object.keys(permissionLabels) as Permission[], dataScope: 'ALL', departments: [] })}>超级管理员</button></div>
      <p>{access.role === 'SUPER_ADMIN' ? '超级管理员拥有全部资产、用户管理、日志、设置和数据导入导出权限，不受部门或本人范围限制。' : '驾驶舱对所有登录用户开放。资产管理员只管理资产，不包含用户、日志和系统设置权限。'}</p>
      <div className="access-permissions">{(Object.entries(permissionLabels) as [Permission, string][]).map(([permission, label]) => <label key={permission}><input type="checkbox" disabled={accessSaving || access.role === 'SUPER_ADMIN'} checked={access.permissions.includes(permission)} onChange={event => togglePermission(permission, event.target.checked)} /><span>{label}</span></label>)}</div>
      <div className="user-form-grid single-field"><label><span>数据范围</span><select disabled={accessSaving || access.role === 'SUPER_ADMIN'} value={access.dataScope} onChange={event => setAccess({ ...access, dataScope: event.target.value as AccessPayload['dataScope'] })}>{Object.entries(scopeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      {access.dataScope === 'DEPARTMENT' && <label><span>允许访问的部门（与资产归属部门一致，用逗号分隔）</span><input maxLength={6000} value={departmentText} onChange={event => setDepartmentText(event.target.value)} placeholder="例如：研发部，行政部" /></label>}</div>
      <p>{access.dataScope === 'SELF' ? '仅展示已明确关联此账号的领用资产；旧数据不会按同名自动关联。' : '只允许访问所选范围内的资产，导出同样受此范围限制。'}批量导入需要“全部资产”范围。角色变更后该账号需要重新登录。</p>
      {access.role === 'SUPER_ADMIN' && accessUser.role !== 'SUPER_ADMIN' && <label className="access-enabled"><input type="checkbox" checked={superAdminConfirmed} disabled={accessSaving} onChange={event => setSuperAdminConfirmed(event.target.checked)} />我确认授予该账号全系统和用户权限管理权限</label>}
      <label className="access-enabled"><input type="checkbox" disabled={accessSaving} checked={access.enabled} onChange={event => setAccess({ ...access, enabled: event.target.checked })} />启用账号（取消后不能继续访问）</label>
      <div className="modal-actions"><button className="button ghost" disabled={accessSaving} onClick={() => setAccessUser(null)}>取消</button><button className="button primary" disabled={accessSaving || (access.role === 'SUPER_ADMIN' && accessUser.role !== 'SUPER_ADMIN' && !superAdminConfirmed)} onClick={() => void saveAccess()}>{accessSaving ? '保存中…' : '保存权限'}</button></div>
    </div></div>, document.body)}
    {deleteTarget && <ConfirmDialog
      title="删除普通用户"
      description={`确认删除“${deleteTarget.username}”吗？删除后该账号将立即无法登录，但不会删除该用户过去操作过的资产资料。`}
      busy={deletingId === deleteTarget.id}
      onCancel={() => deletingId == null && setDeleteTarget(null)}
      onConfirm={() => void deleteUser(deleteTarget)}
    />}
    {avatarDeleteTarget && <ConfirmDialog
      title="清除用户头像"
      description={`确认清除“${avatarDeleteTarget.username}”的头像吗？账号和其他资料不会受影响，该用户之后仍可重新上传头像。`}
      busy={avatarDeletingId === avatarDeleteTarget.id}
      onCancel={() => avatarDeletingId == null && setAvatarDeleteTarget(null)}
      onConfirm={() => void clearAvatar(avatarDeleteTarget)}
    />}
  </div>
}
