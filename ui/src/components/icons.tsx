// Line icons at a 24-unit grid, 1.6 stroke — thin enough to sit beside
// hairline borders without becoming the heaviest thing on the page.

interface Props {
  size?: number
  className?: string
}

function Svg({ size = 16, className, children }: Props & { children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      {children}
    </svg>
  )
}

export const PlusIcon = (p: Props) => (
  <Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>
)

export const SendIcon = (p: Props) => (
  <Svg {...p}><path d="M4 12h15M13 6l6 6-6 6" /></Svg>
)

export const StopIcon = (p: Props) => (
  <Svg {...p}><rect x="7" y="7" width="10" height="10" rx="1" /></Svg>
)

export const TrashIcon = (p: Props) => (
  <Svg {...p}><path d="M4 7h16M9 7V5h6v2M6 7l1 12h10l1-12" /></Svg>
)

export const RailIcon = (p: Props) => (
  <Svg {...p}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></Svg>
)

export const SourcesIcon = (p: Props) => (
  <Svg {...p}><path d="M4 5h9a3 3 0 0 1 3 3v11a2.5 2.5 0 0 0-2.5-2.5H4z" /><path d="M20 5v11.5" /></Svg>
)

export const CloseIcon = (p: Props) => (
  <Svg {...p}><path d="M6 6l12 12M18 6L6 18" /></Svg>
)

export const SunIcon = (p: Props) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
)

export const MoonIcon = (p: Props) => (
  <Svg {...p}><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z" /></Svg>
)

export const AutoIcon = (p: Props) => (
  <Svg {...p}><circle cx="12" cy="12" r="8" /><path d="M12 4v16a8 8 0 0 0 0-16z" fill="currentColor" /></Svg>
)
