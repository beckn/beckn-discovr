import { useState, type FormEvent } from 'react'

interface Props {
  onSearch: (q: string) => void
  loading: boolean
}

export default function SearchBar({ onSearch, loading }: Props) {
  const [value, setValue] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    onSearch(value)
  }

  return (
    <form className="search-bar" onSubmit={submit}>
      <svg className="search-icon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          d="M21 21l-4.3-4.3M11 19a8 8 0 100-16 8 8 0 000 16z"
        />
      </svg>
      <input
        className="search-input"
        type="text"
        placeholder="Search catalogs and resources — try “coffee”, “gold”, or leave blank to browse all"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        autoFocus
      />
      <button className="search-btn" type="submit" disabled={loading}>
        {loading ? 'Searching…' : 'Search'}
      </button>
    </form>
  )
}
