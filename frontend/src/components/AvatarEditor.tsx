import { useEffect, useRef, useState } from 'react'
import type { AuthUser } from '../types'
import { CloseIcon, TrashIcon } from './Icons'
import { DingTalkBinding } from './DingTalkBinding'

const CROP_SIZE = 220
const OUTPUT_SIZE = 512

type Point = { x: number; y: number }
type ImageSize = { width: number; height: number }

const displaySize = (size: ImageSize, zoom: number) => {
  const coverScale = Math.max(CROP_SIZE / size.width, CROP_SIZE / size.height)
  return { width: size.width * coverScale * zoom, height: size.height * coverScale * zoom }
}

const clampPosition = (position: Point, size: ImageSize, zoom: number): Point => {
  const displayed = displaySize(size, zoom)
  const maxX = Math.max(0, (displayed.width - CROP_SIZE) / 2)
  const maxY = Math.max(0, (displayed.height - CROP_SIZE) / 2)
  return {
    x: Math.max(-maxX, Math.min(maxX, position.x)),
    y: Math.max(-maxY, Math.min(maxY, position.y)),
  }
}

interface Props {
  user: AuthUser
  revision: number
  busy: boolean
  onClose: () => void
  onUpload: (file: File) => Promise<void>
  onDelete: () => Promise<void>
  onError: (message: string) => void
}

export function AvatarEditor({ user, revision, busy, onClose, onUpload, onDelete, onError }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const imageRef = useRef<HTMLImageElement>(null)
  const dragRef = useRef<{ pointerId: number; x: number; y: number; position: Point } | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [previewUrl, setPreviewUrl] = useState('')
  const [imageSize, setImageSize] = useState<ImageSize | null>(null)
  const [position, setPosition] = useState<Point>({ x: 0, y: 0 })
  const [zoom, setZoom] = useState(1)
  const [dirty, setDirty] = useState(false)
  const [processing, setProcessing] = useState(false)

  useEffect(() => {
    if (!file) { setPreviewUrl(''); return }
    const url = URL.createObjectURL(file)
    setPreviewUrl(url)
    return () => URL.revokeObjectURL(url)
  }, [file])

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => event.key === 'Escape' && !busy && !processing && onClose()
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose, processing])

  const chooseFile = (selected?: File) => {
    if (!selected) return
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(selected.type)) {
      onError('仅支持JPG、PNG或WebP图片'); return
    }
    if (selected.size > 2 * 1024 * 1024) {
      onError('头像不能超过2MB'); return
    }
    setFile(selected)
    setImageSize(null)
    setPosition({ x: 0, y: 0 })
    setZoom(1)
    setDirty(true)
  }

  const imageUrl = previewUrl || (user.hasAvatar ? `/api/users/${user.id}/avatar?v=${revision}` : '')
  const displayed = imageSize ? displaySize(imageSize, zoom) : null
  const working = busy || processing

  const updateZoom = (nextZoom: number) => {
    if (!imageSize) return
    setZoom(nextZoom)
    setPosition((current) => clampPosition(current, imageSize, nextZoom))
    setDirty(true)
  }

  const resetCrop = () => {
    setZoom(1)
    setPosition({ x: 0, y: 0 })
    setDirty(Boolean(imageUrl))
  }

  const startDragging = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!imageSize || working) return
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, position }
  }

  const dragImage = (event: React.PointerEvent<HTMLDivElement>) => {
    const start = dragRef.current
    if (!start || start.pointerId !== event.pointerId || !imageSize) return
    const next = clampPosition({
      x: start.position.x + event.clientX - start.x,
      y: start.position.y + event.clientY - start.y,
    }, imageSize, zoom)
    setPosition(next)
    setDirty(true)
  }

  const stopDragging = (event: React.PointerEvent<HTMLDivElement>) => {
    if (dragRef.current?.pointerId === event.pointerId) dragRef.current = null
  }

  const createCroppedFile = () => new Promise<File>((resolve, reject) => {
    const image = imageRef.current
    if (!image || !imageSize) { reject(new Error('头像图片尚未加载完成')); return }
    const canvas = document.createElement('canvas')
    canvas.width = OUTPUT_SIZE
    canvas.height = OUTPUT_SIZE
    const context = canvas.getContext('2d')
    if (!context) { reject(new Error('当前浏览器无法裁切图片')); return }
    const rendered = displaySize(imageSize, zoom)
    const outputScale = OUTPUT_SIZE / CROP_SIZE
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
    context.drawImage(
      image,
      ((CROP_SIZE - rendered.width) / 2 + position.x) * outputScale,
      ((CROP_SIZE - rendered.height) / 2 + position.y) * outputScale,
      rendered.width * outputScale,
      rendered.height * outputScale,
    )
    canvas.toBlob((blob) => {
      if (!blob) { reject(new Error('头像裁切失败')); return }
      resolve(new File([blob], `avatar-${Date.now()}.jpg`, { type: 'image/jpeg' }))
    }, 'image/jpeg', 0.92)
  })

  const saveAvatar = async () => {
    setProcessing(true)
    try { await onUpload(await createCroppedFile()) }
    catch (error) { onError(error instanceof Error ? error.message : '头像裁切失败') }
    finally { setProcessing(false) }
  }

  return <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && !working && onClose()}>
    <section className="avatar-editor" role="dialog" aria-modal="true" aria-labelledby="avatar-editor-title">
      <button className="icon-button modal-close" disabled={working} onClick={onClose} aria-label="关闭"><CloseIcon /></button>
      <h2 id="avatar-editor-title">设置我的头像</h2>
      <p>拖动图片调整位置，让人物处于头像框正中间。</p>
      <div
        className={`avatar-crop-stage ${imageUrl ? 'has-image' : ''}`}
        onPointerDown={startDragging}
        onPointerMove={dragImage}
        onPointerUp={stopDragging}
        onPointerCancel={stopDragging}
      >
        {imageUrl ? <img
          ref={imageRef}
          className="avatar-crop-image"
          src={imageUrl}
          alt="头像裁切预览"
          draggable={false}
          onLoad={(event) => {
            const nextSize = { width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight }
            setImageSize(nextSize)
            setPosition((current) => clampPosition(current, nextSize, zoom))
          }}
          style={displayed ? {
            width: `${displayed.width}px`,
            height: `${displayed.height}px`,
            transform: `translate(-50%, -50%) translate(${position.x}px, ${position.y}px)`,
          } : undefined}
        /> : <span className="avatar-initial"><b>{user.username.slice(0, 1).toUpperCase()}</b></span>}
        {imageUrl && <><span className="avatar-crop-mask" /><span className="avatar-drag-hint">拖动调整</span></>}
      </div>
      {imageUrl && <div className="avatar-crop-controls">
        <span>缩小</span>
        <input aria-label="头像缩放" type="range" min="1" max="3" step="0.01" value={zoom} disabled={working || !imageSize} onChange={(event) => updateZoom(Number(event.target.value))} />
        <span>放大</span>
        <button type="button" disabled={working} onClick={resetCrop}>复位</button>
      </div>}
      <input ref={inputRef} className="avatar-file-input" type="file" accept="image/jpeg,image/png,image/webp" onChange={(event) => chooseFile(event.target.files?.[0])} />
      <button className="button avatar-choose" disabled={working} onClick={() => inputRef.current?.click()}>{file ? '重新选择图片' : '选择图片'}</button>
      <small>支持 JPG、PNG、WebP，最大 2MB；保存结果与上方预览一致。</small>
      <DingTalkBinding />
      <div className="modal-actions avatar-actions">
        {user.hasAvatar && <button className="button avatar-remove" disabled={working} onClick={() => void onDelete()}><TrashIcon />删除头像</button>}
        <button className="button ghost" disabled={working} onClick={onClose}>取消</button>
        <button className="button primary" disabled={!imageUrl || !imageSize || (!file && !dirty) || working} onClick={() => void saveAvatar()}>{working ? '保存中…' : '保存头像'}</button>
      </div>
    </section>
  </div>
}
