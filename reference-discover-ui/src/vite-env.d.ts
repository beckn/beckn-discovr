/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional environment label shown in the header (e.g. "demo", "staging"). */
  readonly VITE_APP_ENV?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
