import type { ReactNode } from "react";

import type { NavItem } from "../navigation";

export function PageHeader({ item, title, subtitle, actions }: {
  item: NavItem;
  title?: string;
  subtitle?: string;
  actions?: ReactNode;
}) {
  const Icon = item.icon;
  return (
    <header className={`page-header tone-${item.tone}`}>
      <span className="bubble solid large" aria-hidden="true">
        <Icon size={28} stroke={1.8} />
      </span>
      <div style={{ flex: 1 }}>
        <h1>{title ?? item.label}</h1>
        <p>{subtitle ?? item.description}</p>
      </div>
      {actions}
    </header>
  );
}
