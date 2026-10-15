import { useRef, useState } from 'react'
import { api } from '../api'
import type { CsvImportPreview } from '../types'
import { DownloadIcon, UploadIcon } from './Icons'

interface Props {
  canExport: boolean
  canImport: boolean
  onNotify: (text: string, kind?: 'ok' | 'error') => void
  onImported: () => Promise<void>
}

export function AssetTransfer({ canExport, canImport, onNotify, onImported }: Props) {
  const [downloading, setDownloading] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<CsvImportPreview | null>(null)
  const [checking, setChecking] = useState(false)
  const [importing, setImporting] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const downloadAssets = async () => {
    setDownloading(true)
    try {
      await api.downloadAssetCsv()
      onNotify('授权范围内的资产数据已导出')
    } catch (error) {
      onNotify(error instanceof Error ? error.message : '资产数据导出失败', 'error')
    } finally {
      setDownloading(false)
    }
  }

  const selectFile = async (selected?: File) => {
    if (!selected) return
    if (!selected.name.toLowerCase().endsWith('.csv')) { onNotify('请选择 CSV 文件', 'error'); return }
    setFile(selected); setPreview(null); setChecking(true)
    try { setPreview(await api.previewAssetCsv(selected)) }
    catch (error) { setFile(null); onNotify(error instanceof Error ? error.message : 'CSV 校验失败', 'error') }
    finally { setChecking(false) }
  }

  const confirmImport = async () => {
    if (!file || !preview?.canImport) return
    setImporting(true)
    try {
      const result = await api.importAssetCsv(file)
      onNotify(`成功导入 ${result.importedCount} 项资产${result.createdLookupCount ? `，并新建 ${result.createdLookupCount} 个选项` : ''}`)
      setFile(null); setPreview(null); if (inputRef.current) inputRef.current.value = ''
      await onImported()
    } catch (error) { onNotify(error instanceof Error ? error.message : 'CSV 导入失败', 'error') }
    finally { setImporting(false) }
  }

  return <section className="transfer-page">
    {canExport && <div className="transfer-card">
      <div className="transfer-copy">
        <span className="transfer-icon"><DownloadIcon /></span>
        <div>
          <h2>资产数据</h2>
        </div>
      </div>
      <button className="button primary transfer-download" disabled={downloading} onClick={() => void downloadAssets()}>
        <DownloadIcon />{downloading ? '正在导出…' : '导出授权范围数据'}
      </button>
    </div>}

    {canImport && <div className="transfer-import-card">
      <div className="transfer-copy"><span className="transfer-icon import"><UploadIcon /></span><div><h2>导入 CSV</h2></div></div>
      <div className="transfer-import-actions"><input ref={inputRef} hidden type="file" accept=".csv,text/csv" onChange={(event) => void selectFile(event.target.files?.[0])} /><button className="button ghost" disabled={checking || importing} onClick={() => inputRef.current?.click()}><UploadIcon />{checking ? '正在校验…' : file ? '重新选择' : '选择 CSV'}</button>{preview?.canImport && <button className="button primary" disabled={importing} onClick={() => void confirmImport()}>{importing ? '正在导入…' : `确认导入 ${preview.validRows} 项`}</button>}</div>
    </div>}

    {file && <div className="csv-file-line"><strong>{file.name}</strong><span>{(file.size / 1024).toFixed(1)} KB</span></div>}
    {preview && <div className={`csv-preview ${preview.canImport ? 'ready' : 'invalid'}`}>
      <div className="csv-preview-summary"><strong>{preview.canImport ? '校验通过，可以导入' : '校验未通过，请修改文件'}</strong><span>共 {preview.totalRows} 行 · 有效 {preview.validRows} 行 · 错误 {preview.errors.length} 处 · 提醒 {preview.warnings.length} 处</span></div>
      {preview.errors.length > 0 && <div className="csv-messages errors"><h3>必须修正</h3>{preview.errors.slice(0, 12).map((message, index) => <p key={`${message.row}-${message.field}-${index}`}>第 {message.row} 行 · {message.field}：{message.message}</p>)}{preview.errors.length > 12 && <small>还有 {preview.errors.length - 12} 处错误未显示</small>}</div>}
      {preview.warnings.length > 0 && <div className="csv-messages warnings"><h3>导入提醒</h3>{preview.warnings.slice(0, 8).map((message, index) => <p key={`${message.row}-${message.field}-${index}`}>第 {message.row} 行 · {message.message}</p>)}{preview.warnings.length > 8 && <small>还有 {preview.warnings.length - 8} 条提醒未显示</small>}</div>}
      <div className="csv-sample"><div className="csv-sample-head"><span>行</span><span>资产编号</span><span>资产名称</span><span>状态</span></div>{preview.sample.map((row) => <div key={row.row}><span>{row.row}</span><span>{row.assetTag || '—'}</span><span>{row.name || '—'}</span><span className={row.valid ? 'valid' : 'invalid'}>{row.valid ? row.status || '待确认' : '有错误'}</span></div>)}</div>
    </div>}
  </section>
}
