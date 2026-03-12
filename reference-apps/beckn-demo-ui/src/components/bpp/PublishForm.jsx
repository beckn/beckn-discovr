import React, { useState, useRef } from 'react'
import { publishCatalog } from '../../api/client'

export default function PublishForm() {
  const [file, setFile] = useState(null)
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState(null)
  const [preview, setPreview] = useState(null)
  const inputRef = useRef()

  function handleFileChange(e) {
    const f = e.target.files[0]
    if (!f) return
    setFile(f)
    setStatus(null)
    const reader = new FileReader()
    reader.onload = (ev) => {
      try {
        const parsed = JSON.parse(ev.target.result)
        setPreview(parsed)
      } catch {
        setPreview(null)
        setStatus({ ok: false, msg: 'Invalid JSON file.' })
      }
    }
    reader.readAsText(f)
  }

  function handleDrop(e) {
    e.preventDefault()
    const f = e.dataTransfer.files[0]
    if (f) {
      inputRef.current.files = e.dataTransfer.files
      handleFileChange({ target: { files: [f] } })
    }
  }

  async function handleSubmit(e) {
    e.preventDefault()
    if (!preview) return
    setLoading(true)
    setStatus(null)
    try {
      await publishCatalog(preview)
      setStatus({ ok: true, msg: 'Catalog published successfully! ACK received.' })
      setTimeout(() => setStatus(null), 4000)
    } catch (err) {
      setStatus({ ok: false, msg: err.message })
    } finally {
      setLoading(false)
    }
  }

  const catalogCount = preview?.message?.catalogs?.length || 0
  const itemCount = preview?.message?.catalogs?.reduce((s, c) => s + (c['beckn:items']?.length || 0), 0) || 0

  return (
    <div style={styles.card}>
      <h3 style={styles.cardTitle}>Publish Catalog</h3>

      <form onSubmit={handleSubmit}>
        <div
          style={styles.dropzone}
          onDragOver={e => e.preventDefault()}
          onDrop={handleDrop}
          onClick={() => inputRef.current.click()}
        >
          <input ref={inputRef} type="file" accept=".json,application/json" style={{ display: 'none' }} onChange={handleFileChange} />
          {file ? (
            <div style={styles.fileInfo}>
              <span style={styles.fileIcon}>📄</span>
              <div>
                <div style={styles.fileName}>{file.name}</div>
                <div style={styles.fileMeta}>{(file.size / 1024).toFixed(1)} KB</div>
              </div>
            </div>
          ) : (
            <div style={styles.dropHint}>
              <span style={styles.uploadIcon}>⬆</span>
              <div style={styles.dropText}>Drop your catalog JSON here</div>
              <div style={styles.dropSub}>or click to browse</div>
            </div>
          )}
        </div>

        {preview && (
          <div style={styles.previewBox}>
            <div style={styles.previewRow}>
              <div style={styles.previewStat}>
                <span style={styles.statNum}>{catalogCount}</span>
                <span style={styles.statLabel}>Catalog{catalogCount !== 1 ? 's' : ''}</span>
              </div>
              <div style={styles.previewStat}>
                <span style={styles.statNum}>{itemCount}</span>
                <span style={styles.statLabel}>Item{itemCount !== 1 ? 's' : ''}</span>
              </div>
              <div style={styles.previewStat}>
                <span style={styles.statNum}>{preview.context?.bpp_id?.split('.')[0] || '—'}</span>
                <span style={styles.statLabel}>BPP</span>
              </div>
            </div>
          </div>
        )}

        <button type="submit" disabled={!preview || loading} style={preview ? styles.btn : styles.btnDisabled}>
          {loading ? 'Publishing…' : 'Publish Catalog'}
        </button>
      </form>

      {status && (
        <div style={status.ok ? styles.alertSuccess : styles.alertError}>
          {status.ok ? '✅ ' : '❌ '}{status.msg}
        </div>
      )}
    </div>
  )
}

const styles = {
  card: { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 4px rgba(0,0,0,0.08)' },
  cardTitle: { fontSize: 16, fontWeight: 600, marginBottom: 20, color: '#0f172a' },
  dropzone: {
    border: '2px dashed #e5e7eb', borderRadius: 12, padding: 32,
    textAlign: 'center', cursor: 'pointer', marginBottom: 16,
    transition: 'border-color 0.2s', background: '#fafafa'
  },
  dropHint: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 },
  uploadIcon: { fontSize: 28 },
  dropText: { fontSize: 14, fontWeight: 500, color: '#374151' },
  dropSub: { fontSize: 12, color: '#9ca3af' },
  fileInfo: { display: 'flex', alignItems: 'center', gap: 12 },
  fileIcon: { fontSize: 32 },
  fileName: { fontSize: 14, fontWeight: 500, color: '#374151' },
  fileMeta: { fontSize: 12, color: '#9ca3af' },
  previewBox: { background: '#f0f9ff', borderRadius: 10, padding: 16, marginBottom: 16 },
  previewRow: { display: 'flex', gap: 24 },
  previewStat: { display: 'flex', flexDirection: 'column', alignItems: 'center' },
  statNum: { fontSize: 22, fontWeight: 700, color: '#0284C7' },
  statLabel: { fontSize: 11, color: '#6b7280', marginTop: 2 },
  btn: { width: '100%', padding: '11px 20px', background: '#0284C7', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, fontSize: 14 },
  btnDisabled: { width: '100%', padding: '11px 20px', background: '#e5e7eb', color: '#9ca3af', border: 'none', borderRadius: 8, fontWeight: 600, fontSize: 14, cursor: 'not-allowed' },
  alertSuccess: { marginTop: 16, padding: 16, background: '#d1fae5', borderRadius: 10 },
  alertError: { marginTop: 16, padding: 16, background: '#fee2e2', borderRadius: 10 },
  alertTitle: { fontSize: 14, fontWeight: 600, color: '#1a1a2e' },
  detail: { marginTop: 10, fontSize: 11, color: '#374151', whiteSpace: 'pre-wrap', maxHeight: 150, overflow: 'auto' }
}
