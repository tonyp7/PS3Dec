/** Follow the OS light/dark preference by toggling the `dark` class shadcn's theme keys off. */
export function followSystemTheme(
  root: HTMLElement = document.documentElement,
  matchMedia: (q: string) => MediaQueryList = window.matchMedia.bind(window),
): () => void {
  const query = matchMedia('(prefers-color-scheme: dark)')
  const apply = () => root.classList.toggle('dark', query.matches)
  apply()
  query.addEventListener('change', apply)
  return () => query.removeEventListener('change', apply)
}
