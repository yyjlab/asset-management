import { useEffect, useState } from 'react'
import { api } from '../api'
import { CheckIcon, SaveIcon, SettingsIcon } from './Icons'
import { DeploymentIntegration } from './DeploymentIntegration'
import { WindowsCollection } from './WindowsCollection'

interface Props {
  onNotify: (text: string, kind?: 'ok' | 'error') => void
}

export function SystemSettings({ onNotify }: Props) {
  const [tab, setTab] = useState<'qr' | 'deployment' | 'windows'>('qr')
  const [value, setValue] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [configuredOpen, setConfiguredOpen] = useState(false)

  useEffect(() => {
    api.getQrSetting().then((qrResult) => {
      setValue(qrResult.qrBaseUrl || '')
    })
      .catch((error) => onNotify(error instanceof Error ? error.message : '读取设置失败', 'error'))
      .finally(() => setLoading(false))
  }, [])

  const save = async () => {
    setSaving(true)
    try {
      const updated = await api.updateQrSetting(value)
      setValue(updated.qrBaseUrl)
      setConfiguredOpen(true)
    } catch (error) {
      onNotify(error instanceof Error ? error.message : '保存设置失败', 'error')
    } finally { setSaving(false) }
  }

  return <section className="settings-page">
    <nav className="deployment-tabs" aria-label="系统设置分类">
      <button className={`button ${tab === 'qr' ? 'primary' : 'ghost'}`} onClick={() => setTab('qr')}>二维码设置</button>
      <button className={`button ${tab === 'deployment' ? 'primary' : 'ghost'}`} onClick={() => setTab('deployment')}>装机平台接入</button>
      <button className={`button ${tab === 'windows' ? 'primary' : 'ghost'}`} onClick={() => setTab('windows')}>Windows 采集</button>
    </nav>
    {tab === 'windows' ? <WindowsCollection onNotify={onNotify} /> : tab === 'deployment' ? <DeploymentIntegration onNotify={onNotify} /> : loading
      ? <div className="settings-loading"><span className="spinner" />正在读取设置…</div>
      : <div className="settings-shell"><article className="settings-card">
      <header className="settings-head"><div className="settings-icon"><SettingsIcon /></div><div><h2>系统设置</h2><p>管理二维码访问地址</p></div></header>
      <div className="settings-section-title"><strong>二维码设置</strong><span>为每个部署实例配置自己的长期访问地址</span></div>
      <div className="settings-field">
        <label htmlFor="qr-base-url">二维码访问地址</label>
        <div><input id="qr-base-url" type="url" value={value} onChange={(event) => setValue(event.target.value)} placeholder="例如 https://asset.example.com" /><button className="button primary" disabled={saving || !value.trim()} onClick={() => void save()}><SaveIcon />{saving ? '保存中…' : '保存设置'}</button></div>
        <small>必须填写完整的 http:// 或 https:// 地址。建议使用内部 DNS 固定域名，不要填写可能迁移的服务器 IP。</small>
      </div>
    </article></div>}
    {configuredOpen && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setConfiguredOpen(false)}>
      <section className="settings-success-dialog" role="alertdialog" aria-modal="true" aria-labelledby="configured-title">
        <span><CheckIcon /></span>
        <h3 id="configured-title">已配置</h3>
        <button type="button" className="button primary" onClick={() => setConfiguredOpen(false)}>确定</button>
      </section>
    </div>}
  </section>
}
