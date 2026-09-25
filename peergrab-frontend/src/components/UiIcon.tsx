import type { ReactNode } from 'react';

export type UiIconName =
  | 'grid' | 'plus' | 'tasks' | 'wallet' | 'award' | 'bell' | 'menu'
  | 'arrowRight' | 'chevronRight' | 'logOut' | 'check' | 'shield'
  | 'zap' | 'lock' | 'close' | 'sparkles' | 'refresh' | 'activity';

const paths: Record<UiIconName, ReactNode> = {
  grid: <><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></>,
  plus: <><path d="M12 5v14M5 12h14" /></>,
  tasks: <><rect x="4" y="4" width="16" height="16" rx="3" /><path d="m8 10 1.5 1.5L12 9M8 16h8" /></>,
  wallet: <><rect x="3" y="6" width="18" height="15" rx="3" /><path d="M3 9V6a3 3 0 0 1 3-3h12M16 14h5M16 14a1 1 0 1 0 2 0 1 1 0 0 0-2 0" /></>,
  award: <><circle cx="12" cy="9" r="5" /><path d="m8.5 13-1.5 8 5-2.5 5 2.5-1.5-8" /></>,
  bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9ZM10 21h4" /></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
  arrowRight: <><path d="M4 12h16m-6-6 6 6-6 6" /></>,
  chevronRight: <><path d="m9 5 7 7-7 7" /></>,
  logOut: <><path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M14 8l4 4-4 4M18 12H9" /></>,
  check: <><path d="m5 12 4 4L19 6" /></>,
  shield: <><path d="M12 2 4 5v6c0 5 3.4 8.5 8 11 4.6-2.5 8-6 8-11V5l-8-3Z" /><path d="m9 12 2 2 4-4" /></>,
  zap: <><path d="m13 2-9 12h7l-1 8 10-12h-7l1-8Z" /></>,
  lock: <><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></>,
  close: <><path d="M5 5 19 19M19 5 5 19" /></>,
  sparkles: <><path d="m12 2 1.5 5.5L19 9l-5.5 1.5L12 16l-1.5-5.5L5 9l5.5-1.5L12 2ZM19 17l.7 2.3L22 20l-2.3.7L19 23l-.7-2.3L16 20l2.3-.7L19 17Z" /></>,
  refresh: <><path d="M20 7v5h-5M4 17v-5h5" /><path d="M5 9a7 7 0 0 1 12-2l3 5M4 12l3 5a7 7 0 0 0 12-2" /></>,
  activity: <><path d="M3 12h4l3-7 4 14 3-7h4" /></>,
};

export default function UiIcon({ name, size = 20, className = '' }: {
  name: UiIconName;
  size?: number;
  className?: string;
}) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
         aria-hidden="true" focusable="false">
      {paths[name]}
    </svg>
  );
}
