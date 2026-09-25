export const IDENTITIES = [
  { userId: 1001, name: '发单人 1001', shortName: '发单人', initials: '发', desc: '发布任务、托管资金、确认完成' },
  { userId: 2001, name: '跑腿 2001', shortName: '跑腿伙伴', initials: '跑', desc: '抢单、执行任务、送达' },
  { userId: 2002, name: '跑腿 2002', shortName: '跑腿伙伴', initials: '跑', desc: '和其他跑腿伙伴一起抢单' },
  { userId: 9001, name: '仲裁员 9001', shortName: '平台仲裁', initials: '仲', desc: '处理争议，守护交易公平' },
] as const;

// Local demo credentials are intentionally visible in the demo frontend.
export const DEMO_PASSWORDS: Record<number, string> = {
  1001: 'demo1001',
  2001: 'demo2001',
  2002: 'demo2002',
  9001: 'demo9001',
};

export function demoPasswordFor(userId: number): string {
  return DEMO_PASSWORDS[userId] ?? '';
}
