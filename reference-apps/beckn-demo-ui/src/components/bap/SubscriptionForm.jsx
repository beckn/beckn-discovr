import React, { useState } from 'react'
import { subscribe } from '../../api/client'

const DEFAULT = {
  id: '',
  subscriberName: 'Demo BAP Watcher',
  subscriberUrl: 'https://bap.demo.beckn',
  networkId: 'ondc-retail-grocery',
  networkName: 'Grocery',
  schemaType: 'https://becknprotocol.io/schema/GroceryItem/v1/context.jsonld#GroceryItem',
  callbackUrl: 'http://catalog-publish:8080/catalog/push'
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16)
  })
}

export default function SubscriptionForm() {
  const [form, setForm] = useState({ ...DEFAULT, id: uuid() })
  const [status, setStatus] = useState(null)
  const [loading, setLoading] = useState(false)

  const set = (k) => (e) => setForm(f => ({ ...f, [k]: e.target.value }))

  async function handleSubmit(e) {
    e.preventDefault()
    setLoading(true)
    setStatus(null)
    try {
      const payload = {
        id: form.id,
        subscriber: { name: form.subscriberName, type: 'BAP', url: form.subscriberUrl },
        networks: [{ id: form.networkId, name: form.networkName }],
        schema_types: [form.schemaType],
        callback_url: form.callbackUrl
      }
      const res = await subscribe(payload)
      setStatus({ ok: true, msg: `Subscribed successfully (id: ${form.id})` })
    } catch (err) {
      setStatus({ ok: false, msg: err.message })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={styles.card}>
      <h3 style={styles.cardTitle}>Subscribe to Catalog Updates</h3>
      <form onSubmit={handleSubmit} style={styles.form}>
        <div style={styles.field}>
          <label style={styles.label}>Subscription ID <span style={styles.autoTag}>auto-generated</span></label>
          <input style={styles.inputReadOnly} value={form.id} readOnly />
        </div>
        <Field label="Subscriber Name" value={form.subscriberName} onChange={set('subscriberName')} />
        <Field label="Subscriber URL" value={form.subscriberUrl} onChange={set('subscriberUrl')} />
        <Field label="Network ID" value={form.networkId} onChange={set('networkId')} />
        <Field label="Network Name" value={form.networkName} onChange={set('networkName')} />
        <Field label="Schema Type" value={form.schemaType} onChange={set('schemaType')} />
        <Field label="Callback URL" value={form.callbackUrl} onChange={set('callbackUrl')} />
        <button type="submit" disabled={loading} style={styles.btn}>
          {loading ? 'Subscribing…' : 'Subscribe'}
        </button>
        {status && (
          <div style={status.ok ? styles.alertSuccess : styles.alertError}>
            {status.msg}
          </div>
        )}
      </form>
    </div>
  )
}

function Field({ label, value, onChange }) {
  return (
    <div style={styles.field}>
      <label style={styles.label}>{label}</label>
      <input style={styles.input} value={value} onChange={onChange} required />
    </div>
  )
}

const styles = {
  card: { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 4px rgba(0,0,0,0.08)' },
  cardTitle: { fontSize: 16, fontWeight: 600, marginBottom: 20, color: '#0f172a' },
  form: { display: 'flex', flexDirection: 'column', gap: 12 },
  field: { display: 'flex', flexDirection: 'column', gap: 4 },
  label: { fontSize: 12, fontWeight: 500, color: '#6b7280' },
  input: { padding: '8px 12px', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 13, outline: 'none' },
  autoTag: { fontSize: 10, fontWeight: 600, color: '#0284C7', background: '#e0f2fe', padding: '2px 7px', borderRadius: 20, marginLeft: 6 },
  inputReadOnly: { padding: '8px 12px', border: '1px solid #e5e7eb', borderRadius: 8, fontSize: 13, background: '#f8fafc', color: '#94a3b8', fontFamily: 'monospace' },
  btn: { marginTop: 4, padding: '10px 20px', background: '#0284C7', color: '#fff', border: 'none', borderRadius: 8, fontWeight: 600, fontSize: 14 },
  alertSuccess: { padding: '10px 14px', background: '#d1fae5', color: '#065f46', borderRadius: 8, fontSize: 13 },
  alertError: { padding: '10px 14px', background: '#fee2e2', color: '#991b1b', borderRadius: 8, fontSize: 13 }
}
