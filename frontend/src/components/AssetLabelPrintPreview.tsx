import { useEffect, useMemo, useState } from 'react'

export function AssetLabelPrintPreview() {
  const [loadedIds, setLoadedIds] = useState<number[]>([])
  const [imageUnavailable, setImageUnavailable] = useState(false)
  const request = useMemo(() => {
    const params = new URLSearchParams(window.location.search)
    const assetId = Number(params.get('assetId'))
    const assetTag = params.get('assetTag')?.trim() || ''
    const batch = params.has('assetIds')
    const parts = batch ? (params.get('assetIds') || '').split(',') : [params.get('assetId') || '']
    const ids = parts.map(Number)
    return {
      assetId,
      assetTag,
      batch, ids,
      valid: parts.length <= 29 && parts.every(part => /^[1-9][0-9]*$/.test(part))
        && ids.every(id => Number.isSafeInteger(id) && id > 0) && new Set(ids).size === ids.length,
    }
  }, [])

  useEffect(() => {
    document.title = request.assetTag ? `${request.assetTag} - 标签打印预览` : '标签打印预览'
  }, [request.assetTag])

  if (!request.valid) {
    return <main className="label-print-page"><div className="label-print-error">打印预览地址无效，请返回资产详情页重新打开。</div></main>
  }

  const imageUrl = `/api/assets/${request.assetId}/qr`
  const filename = `asset-${request.assetTag || request.assetId}-label.png`
  const imageLoaded = loadedIds.length === request.ids.length

  return <main className={`label-print-page${request.batch ? ' batch' : ''}`}>
    <header className="label-print-toolbar">
      <div><span>标签打印预览</span><strong>{request.batch ? `本套 ${request.ids.length} 张标签` : request.assetTag || `资产 ${request.assetId}`}</strong></div>
      <div>
        {!request.batch && <a className="button ghost" href={imageUrl} download={filename}>下载 PNG</a>}
        <button className="button primary" type="button" disabled={!imageLoaded || imageUnavailable} onClick={() => window.print()}>{imageLoaded ? '打印标签' : '标签加载中…'}</button>
      </div>
    </header>
    <section className="label-print-canvas" aria-label="60×50毫米资产标签打印预览">
      {imageUnavailable && <div role="alert" className="label-print-error">资产标签加载失败，请确认登录状态后刷新；未加载完整前不能打印。</div>}
      {request.ids.map(id => <div className="label-print-sheet" key={id}><img
        src={`/api/assets/${id}/qr`}
        alt={`${request.batch ? id : request.assetTag || id} 资产标签打印预览`}
        onLoad={() => setLoadedIds(current => current.includes(id) ? current : [...current, id])}
        onError={() => setImageUnavailable(true)}
      /></div>)}
    </section>
  </main>
}
