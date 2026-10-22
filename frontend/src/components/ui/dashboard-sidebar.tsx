import React, { useEffect, useRef, useState } from 'react';
import {
  Search,
  LayoutDashboard,
  FolderOpen,
  ListTodo,
  LogOut,
  ChevronDown,
  ChevronRight,
  Film,
  FileText,
  FileCheck2,
  Users,
  GraduationCap,
  Video,
  Repeat,
  Stamp,
  AudioLines,
  Image,
  Wand2,
  FileType,
  ShieldCheck,
  Sparkles,
  Globe,
  PanelLeftClose,
  PanelLeftOpen,
  Command,
  X
} from 'lucide-react';

export type NavItemData = {
  id: string;
  title: string;
  icon: React.ElementType;
  /** Route to open; items without one are actions (search, logout…) */
  href?: string;
  keywords?: string;
  badge?: number | string;
  shortcut?: string;
  children?: NavItemData[];
};

export type NavGroupData = {
  heading?: string;
  items: NavItemData[];
};

export interface WorkspaceChromeProps {
  path: string;
  title: string;
  displayName: string;
  username: string;
  activeTasks: number;
  collapsed: boolean;
  onCollapsedChange: (collapsed: boolean) => void;
  navigate: (path: string) => void;
  logout: () => void;
}

const SHORTCUT = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K';
const WORKSPACE = '个人工作台';

const mediaTool = (id: string, title: string, icon: React.ElementType, keywords: string): NavItemData =>
  ({ id: `media-${id}`, title, icon, href: `/workspace/media?tool=${id}`, keywords });

/** Lumen's real navigation, in the component's original group layout. */
const navGroups: NavGroupData[] = [
  {
    items: [
      { id: 'search', title: '搜索', icon: Search, shortcut: SHORTCUT },
      { id: 'home', title: '概览', icon: LayoutDashboard, href: '/workspace', keywords: 'home 首页 统计' },
      { id: 'files', title: '我的文件', icon: FolderOpen, href: '/workspace/files', keywords: 'files 上传 下载 文件库' },
      { id: 'tasks', title: '任务中心', icon: ListTodo, href: '/workspace/tasks', keywords: 'tasks 进度 历史 结果' },
    ]
  },
  {
    heading: '工作空间',
    items: [
      { id: 'ai', title: '智能模式', icon: Sparkles, href: '/workspace/ai', keywords: 'ai 智能 自然语言 一句话 多智能体' },
      {
        id: 'media',
        title: '媒体工作台',
        icon: Film,
        keywords: 'media 视频 音频 图片',
        children: [
          mediaTool('compress', '视频压缩', Video, 'compress 目标体积'),
          mediaTool('convert', '格式转换', Repeat, 'convert mp4 mov webm mkv'),
          mediaTool('watermark', '视频水印', Stamp, 'watermark 水印 logo'),
          mediaTool('audio', '音频提取', AudioLines, 'audio mp3 音轨'),
          mediaTool('image', '图片处理', Image, 'image jpg webp 压缩 尺寸'),
          mediaTool('removebg', 'AI 抠图', Wand2, 'removebg 抠图 背景'),
          mediaTool('pdf', 'PDF 转换', FileType, 'pdf 合并 拆分'),
          mediaTool('metadata', '元数据与隐私', ShieldCheck, 'metadata exif 隐私 定位'),
        ]
      },
      { id: 'documents', title: '文档工作台', icon: FileText, href: '/workspace/documents', keywords: 'documents word excel pdf 网页' },
      { id: 'team', title: '团队协作', icon: Users, href: '/workspace/team', keywords: 'team 多人 协作 腾讯 文档 表格 审核 交付' },
      { id: 'specs', title: '材料规范官', icon: FileCheck2, href: '/workspace/specs', keywords: 'specs 参赛 整理 校验 材料 通知' },
      { id: 'learn', title: '学习播放器', icon: GraduationCap, href: '/workspace/learn', keywords: 'learn 教学 课程 知识图谱 代码' },
    ]
  }
];

const bottomItems: NavItemData[] = [
  { id: 'site', title: '返回官网', icon: Globe, href: '/' },
  { id: 'logout', title: '退出登录', icon: LogOut },
];

const flattenItems = (items: NavItemData[]): NavItemData[] => {
  return items.reduce((acc, item) => {
    acc.push(item);
    if (item.children) acc.push(...flattenItems(item.children));
    return acc;
  }, [] as NavItemData[]);
};
const flatNavData = flattenItems([...navGroups.flatMap(g => g.items), ...bottomItems]);
const searchable = flatNavData.filter(i => i.href && !i.children && i.id !== 'site');

/** The nav item for the current route; the media page defaults to tool=compress. */
function activeIdFor(path: string): string {
  const url = new URL(path, window.location.origin);
  if (url.pathname.startsWith('/workspace/media')) {
    const tool = url.searchParams.get('tool') ?? 'compress';
    return flatNavData.some(i => i.id === `media-${tool}`) ? `media-${tool}` : 'media-compress';
  }
  const match = flatNavData
    .filter(i => i.href && i.href !== '/' && (url.pathname === i.href || (i.href !== '/workspace' && url.pathname.startsWith(i.href + '/'))))
    .sort((a, b) => b.href!.length - a.href!.length)[0];
  return match?.id ?? 'home';
}

function WorkspaceSwitcher({ selected, onSelect, onLeave }: { selected?: string, onSelect?: (ws: string) => void, onLeave: () => void }) {
  const [isOpen, setIsOpen] = useState(false);
  const [internalSelected, setInternalSelected] = useState(WORKSPACE);

  const current = selected || internalSelected;
  const handleSelect = onSelect || setInternalSelected;

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        aria-expanded={isOpen}
        className="w-full text-left flex items-center justify-between px-2 py-2 mb-4 rounded-lg hover:bg-black/5 dark:hover:bg-white/5 cursor-pointer transition-colors select-none group"
      >
        <div className="flex items-center gap-3">
          {/* the brand mark used on the official site (Navbar) and the browser tab */}
          <img src="/favicon.svg?v=lumen-glass" width={32} height={32} alt="" aria-hidden="true" className="w-8 h-8 shrink-0 object-contain" />
          <div className="flex flex-col overflow-hidden">
            <span className="text-[13px] font-medium leading-none mb-1 text-foreground truncate max-w-[120px]">{current}</span>
            <span className="text-[11px] text-muted-foreground leading-none">Lumen · 流明智媒</span>
          </div>
        </div>
        <ChevronDown className="w-4 h-4 text-muted-foreground/50 group-hover:text-foreground/70 transition-colors shrink-0" strokeWidth={1.5} />
      </button>

      {isOpen && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
          <div className="absolute top-[52px] left-0 w-full bg-card border border-border/50 rounded-lg shadow-xl z-50 py-1 flex flex-col gap-0.5 animate-in fade-in zoom-in-95 duration-100">
            {[WORKSPACE].map(ws => (
              <button
                type="button"
                key={ws}
                onClick={() => { handleSelect(ws); setIsOpen(false); }}
                className={`text-left px-3 py-2 mx-1 text-[13px] rounded-md cursor-pointer transition-colors ${current === ws ? 'bg-primary/10 text-primary font-medium' : 'text-foreground/80 hover:bg-black/5 dark:hover:bg-white/5'}`}
              >
                {ws}
              </button>
            ))}
            <div className="h-px bg-border/50 my-1 mx-2" />
            <button
              type="button"
              onClick={() => { setIsOpen(false); onLeave(); }}
              className="text-left px-3 py-2 mx-1 text-[13px] text-muted-foreground hover:bg-black/5 dark:hover:bg-white/5 rounded-md cursor-pointer flex items-center gap-2 transition-colors"
            >
              <span className="text-[16px] leading-none mb-0.5">↗</span> 返回官网
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function NavItem({
  item,
  activeId,
  onSelect,
  level = 0
}: {
  item: NavItemData;
  activeId: string;
  onSelect: (item: NavItemData) => void;
  level?: number;
}) {
  const isActive = activeId === item.id;
  const hasChildren = !!item.children;
  const containsActive = !!item.children?.some(c => c.id === activeId);
  const [isOpen, setIsOpen] = useState(containsActive);
  useEffect(() => { if (containsActive) setIsOpen(true); }, [containsActive]);

  const handleClick = (event: React.MouseEvent) => {
    if (hasChildren) {
      setIsOpen(!isOpen);
      return;
    }
    // let the browser open real links in a new tab
    if (item.href && (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)) return;
    event.preventDefault();
    onSelect(item);
  };

  const rowClass = `w-full text-left group flex items-center justify-between px-2.5 py-[7px] rounded-[6px] cursor-pointer transition-all duration-200 select-none
          ${isActive
            ? 'bg-black/5 dark:bg-white/10 text-foreground font-medium'
            : 'text-muted-foreground hover:bg-black/5 dark:hover:bg-white/5 hover:text-foreground/90'
          }
        `;
  const row = (
    <>
      <div className="flex items-center gap-2.5 min-w-0">
        <item.icon
          className={`w-[16px] h-[16px] shrink-0 transition-colors
            ${isActive ? 'text-foreground' : 'text-muted-foreground/70 group-hover:text-foreground/70'}
          `}
          strokeWidth={1.5}
        />
        <span className="text-[13px] tracking-wide truncate">
          {item.title}
        </span>
      </div>

      <div className="flex items-center gap-2">
        {item.shortcut && (
           <kbd className="hidden group-hover:inline-flex items-center justify-center h-5 px-1.5 text-[10px] font-medium font-mono text-muted-foreground/60 bg-background/50 border border-border/50 rounded-[4px] shadow-xs">
             {item.shortcut}
           </kbd>
        )}
        {!!item.badge && (
          <span className="flex items-center justify-center min-w-[20px] h-5 px-1.5 text-[10px] font-medium rounded-full bg-primary/10 text-primary" aria-label={`${item.badge} 个进行中`}>
            {item.badge}
          </span>
        )}
        {hasChildren && (
          <ChevronRight
            className={`w-3.5 h-3.5 text-muted-foreground/50 transition-transform duration-200 ${isOpen ? 'rotate-90' : ''}`}
            strokeWidth={2}
          />
        )}
      </div>
    </>
  );

  return (
    <div className="flex flex-col w-full">
      {item.href && !hasChildren ? (
        <a href={item.href} aria-current={isActive ? 'page' : undefined} className={rowClass}
          style={{ paddingLeft: `${level * 12 + 10}px` }} onClick={handleClick}>{row}</a>
      ) : (
        <button type="button" aria-expanded={hasChildren ? isOpen : undefined} className={rowClass}
          style={{ paddingLeft: `${level * 12 + 10}px` }} onClick={handleClick}>{row}</button>
      )}

      {hasChildren && (
        <div
          className={`grid transition-[grid-template-rows,opacity] duration-300 ease-in-out ${
            isOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'
          }`}
          inert={!isOpen}
        >
          <div className="overflow-hidden min-h-0 relative flex flex-col gap-0.5 mt-0.5">
            <div
              className="absolute top-0 bottom-0 border-l border-black/5 dark:border-white/5"
              style={{ left: `${level * 12 + 17.5}px` }}
            />
            {item.children!.map(child => (
              <NavItem
                key={child.id}
                item={child}
                activeId={activeId}
                onSelect={onSelect}
                level={level + 1}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export function SidebarNav({
  className = '',
  activeId,
  onSelect,
  activeWorkspace,
  onWorkspaceSelect,
  activeTasks = 0,
  onLeave
}: {
  className?: string,
  activeId?: string,
  onSelect?: (item: NavItemData) => void,
  activeWorkspace?: string,
  onWorkspaceSelect?: (ws: string) => void,
  activeTasks?: number,
  onLeave: () => void
}) {
  const [internalId, setInternalId] = useState('home');
  const currentId = activeId !== undefined ? activeId : internalId;
  const handleSelect = onSelect || ((item: NavItemData) => setInternalId(item.id));

  return (
    <div className={`flex flex-col w-[260px] h-full bg-card/50 border-r border-border/50 p-3 font-sans ${className}`}>
      <WorkspaceSwitcher selected={activeWorkspace} onSelect={onWorkspaceSelect} onLeave={onLeave} />

      <nav aria-label="工作台导航" className="flex-1 overflow-y-auto [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none] flex flex-col gap-4 mt-2">
        {navGroups.map((group, idx) => (
          <div key={idx} className="flex flex-col gap-0.5">
            {group.heading && (
              <span className="px-2.5 mb-1 text-[11px] font-semibold tracking-wider text-muted-foreground/50 uppercase">
                {group.heading}
              </span>
            )}
            {group.items.map(item => (
              <NavItem
                key={item.id}
                item={item.id === 'tasks' ? { ...item, badge: activeTasks } : item}
                activeId={currentId}
                onSelect={handleSelect}
              />
            ))}
          </div>
        ))}
      </nav>

      <div className="mt-auto pt-4 border-t border-border/50 flex flex-col gap-0.5">
        {bottomItems.map(item => (
          <NavItem
            key={item.id}
            item={item}
            activeId={currentId}
            onSelect={handleSelect}
          />
        ))}
      </div>
    </div>
  );
}

function SearchDialog({ onClose, onPick }: { onClose: () => void, onPick: (item: NavItemData) => void }) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(0);
  const panel = useRef<HTMLDivElement>(null);
  const term = query.trim().toLowerCase();
  const results = term
    ? searchable.filter(i => `${i.title} ${i.keywords ?? ''}`.toLowerCase().includes(term))
    : [];

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); onClose(); } };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); if (previous?.isConnected) previous.focus(); };
  }, [onClose]);
  useEffect(() => { panel.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }); }, [selected]);

  return (
    <div className="fixed inset-0 z-[80] flex items-start justify-center pt-[15vh] bg-background/40 backdrop-blur-sm px-4">
      <div className="absolute inset-0" onClick={onClose} />
      <div ref={panel} role="dialog" aria-modal="true" aria-label="搜索页面与工具" className="relative w-full max-w-xl bg-card border border-border/50 rounded-xl shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
        <div className="flex items-center px-4 border-b border-border/50">
          <Search className="w-[18px] h-[18px] text-muted-foreground/70 mr-3 shrink-0" strokeWidth={1.5} />
          <input
            autoFocus
            role="combobox"
            aria-expanded={results.length > 0}
            aria-controls="workspace-search-results"
            aria-activedescendant={results[selected] ? `search-${results[selected].id}` : undefined}
            aria-label="搜索页面与工具"
            value={query}
            onChange={e => { setQuery(e.target.value); setSelected(0); }}
            onKeyDown={e => {
              if (e.key === 'ArrowDown') { e.preventDefault(); setSelected(i => results.length ? (i + 1) % results.length : 0); }
              if (e.key === 'ArrowUp') { e.preventDefault(); setSelected(i => results.length ? (i + results.length - 1) % results.length : 0); }
              if (e.key === 'Enter' && results[selected]) { e.preventDefault(); onPick(results[selected]); }
            }}
            className="flex-1 min-w-0 bg-transparent py-4 outline-none text-[14px] text-foreground placeholder:text-muted-foreground/50"
            placeholder="搜索页面、工具或操作…"
          />
          <kbd
            onClick={onClose}
            className="hidden sm:inline-flex items-center justify-center h-5 px-1.5 ml-2 text-[10px] font-medium font-mono text-muted-foreground/70 bg-black/5 dark:bg-white/10 border border-black/10 dark:border-white/10 rounded-[4px] cursor-pointer hover:text-foreground hover:bg-black/10 dark:hover:bg-white/20 transition-colors"
          >
            ESC
          </kbd>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭搜索"
            className="ml-3 p-1 rounded-md text-muted-foreground/70 hover:bg-black/5 dark:hover:bg-white/10 hover:text-foreground transition-colors"
          >
            <X className="w-[18px] h-[18px]" strokeWidth={1.5} />
          </button>
        </div>
        {results.length ? (
          <ul id="workspace-search-results" role="listbox" aria-label="搜索结果" className="p-2 max-h-[45vh] overflow-y-auto flex flex-col gap-0.5">
            {results.map((item, index) => (
              <li key={item.id} id={`search-${item.id}`} role="option" aria-selected={selected === index}>
                <button
                  type="button"
                  tabIndex={-1}
                  onMouseMove={() => setSelected(index)}
                  onClick={() => onPick(item)}
                  className={`w-full flex items-center gap-2.5 px-2.5 py-2 rounded-[6px] text-left text-[13px] transition-colors ${selected === index ? 'bg-black/5 dark:bg-white/10 text-foreground' : 'text-muted-foreground'}`}
                >
                  <item.icon className="w-[16px] h-[16px] shrink-0" strokeWidth={1.5} />
                  {item.title}
                  {selected === index && <span className="ml-auto text-[11px] text-muted-foreground/60">↵</span>}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <div className="p-2 py-8 flex flex-col items-center justify-center">
             <Command className="w-6 h-6 text-muted-foreground/30 mb-2" strokeWidth={1.5} />
             <p className="text-[13px] text-muted-foreground font-medium">{term ? '没有找到匹配的页面或工具' : '输入页面或工具名称…'}</p>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The component's preview shell, stretched to the full window: a 260px sidebar that collapses to 0,
 * a 56px toolbar with the breadcrumb, and the search dialog. The routed Vue page fills the rest.
 */
export default function WorkspaceChrome(props: WorkspaceChromeProps) {
  const [mobile, setMobile] = useState(() => window.matchMedia('(max-width: 767px)').matches);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const isOpen = mobile ? mobileOpen : !props.collapsed;
  const activeId = activeIdFor(props.path);

  useEffect(() => {
    const media = window.matchMedia('(max-width: 767px)');
    const onChange = () => { setMobile(media.matches); setMobileOpen(false); };
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); setIsSearchOpen(open => !open); }
      if (e.key === 'Escape' && mobileOpen) setMobileOpen(false);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [mobileOpen]);

  const closeSearch = React.useCallback(() => setIsSearchOpen(false), []);
  const handleSelect = (item: NavItemData) => {
    if (item.id === 'search') {
      setMobileOpen(false);
      setIsSearchOpen(true);
      return;
    }
    if (item.id === 'logout') { props.logout(); return; }
    if (item.href) { setMobileOpen(false); props.navigate(item.href); }
  };
  const toggle = () => mobile ? setMobileOpen(!mobileOpen) : props.onCollapsedChange(!props.collapsed);
  const initial = Array.from(props.displayName)[0]?.toUpperCase() || 'L';

  return (
    <>
      {mobile && mobileOpen && <div className="fixed inset-0 z-40 bg-black/20" onClick={() => setMobileOpen(false)} />}

      <aside
        id="workspace-sidebar"
        aria-label="工作台侧栏"
        inert={!isOpen}
        className={`fixed inset-y-0 left-0 z-50 h-full transition-all duration-300 ease-in-out shrink-0 overflow-hidden bg-card border-r border-border/50 ${
          isOpen ? 'w-[260px] opacity-100' : 'w-0 opacity-0 border-none'
        }`}
      >
        <SidebarNav
          className="w-[260px] border-none bg-transparent"
          activeId={activeId}
          onSelect={handleSelect}
          activeWorkspace={WORKSPACE}
          activeTasks={props.activeTasks}
          onLeave={() => props.navigate('/')}
        />
      </aside>

      <div className="workspace-toolbar fixed top-0 right-0 z-30 h-14 border-b border-border/50 flex items-center px-4 justify-between bg-card shrink-0 gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <button
            type="button"
            onClick={toggle}
            aria-label={isOpen ? '收起侧栏' : '展开侧栏'}
            aria-expanded={isOpen}
            aria-controls="workspace-sidebar"
            className="p-1.5 rounded-md text-muted-foreground hover:bg-black/5 dark:hover:bg-white/5 hover:text-foreground transition-colors"
          >
            {isOpen ? <PanelLeftClose className="w-[18px] h-[18px]" strokeWidth={1.5} /> : <PanelLeftOpen className="w-[18px] h-[18px]" strokeWidth={1.5} />}
          </button>
          <nav aria-label="面包屑" className="flex items-center gap-2 text-sm text-muted-foreground min-w-0">
            <span className="truncate shrink-0">{WORKSPACE}</span>
            <span>/</span>
            <span aria-current="page" className="font-medium text-foreground truncate">{props.title}</span>
          </nav>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          <button
            type="button"
            onClick={() => setIsSearchOpen(true)}
            className="w-64 h-8 bg-black/5 dark:bg-white/5 rounded-md hidden md:flex items-center gap-2 px-2.5 text-[13px] text-muted-foreground/70 hover:text-muted-foreground transition-colors"
          >
            <Search className="w-[15px] h-[15px] shrink-0" strokeWidth={1.5} />
            <span className="flex-1 text-left">搜索页面与工具</span>
            <kbd className="text-[10px] font-mono text-muted-foreground/60">{SHORTCUT}</kbd>
          </button>
          <div
            title={`${props.displayName} · @${props.username}`}
            aria-label={`当前用户：${props.displayName}`}
            className="w-8 h-8 bg-primary/10 rounded-full border border-primary/20 flex items-center justify-center text-[12px] font-medium text-foreground"
          >
            {initial}
          </div>
        </div>
      </div>

      {isSearchOpen && <SearchDialog onClose={closeSearch} onPick={item => { setIsSearchOpen(false); handleSelect(item); }} />}
    </>
  );
}
