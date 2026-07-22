import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}
interface State {
  error: Error | null
}

/** Catches render-time errors so a single malformed record can't blank the whole app. */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Surface to the console for debugging; the UI still shows a friendly message.
    console.error('Render error:', error, info)
  }

  render() {
    if (this.state.error) {
      return (
        <div className="banner banner--error" role="alert">
          <strong>Something went wrong rendering the results.</strong> The data may be in an
          unexpected shape. Try another search.
          <button className="banner-retry" onClick={() => this.setState({ error: null })}>
            Dismiss
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
