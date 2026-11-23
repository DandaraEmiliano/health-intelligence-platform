import { IconBell, IconHeart, IconLogout } from "@tabler/icons-react";
import type { ReactNode } from "react";
import { NavLink } from "react-router";

import { useNotifications } from "./api/queries";
import { signOut, useSession } from "./auth/session";
import { NAV_ITEMS } from "./navigation";

export function Shell({ children }: { children: ReactNode }) {
  const session = useSession();
  const notifications = useNotifications();
  const unread = notifications.data?.filter((n) => n.status === "UNREAD").length ?? 0;
  return (
    <div className="shell">
      <aside className="sidebar">
        <NavLink to="/" className="brand tone-pink">
          <span className="bubble" aria-hidden="true"><IconHeart size={18} /></span>
          Health Intelligence
        </NavLink>
        <nav className="nav" aria-label="Principal">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink key={item.path} to={item.path} end={item.path === "/"} className={`tone-${item.tone}`}>
                <span className="bubble" aria-hidden="true"><Icon size={18} /></span>
                {item.label}
                {item.plannedPhase && <span className="soon">em breve</span>}
              </NavLink>
            );
          })}
        </nav>
        <div className="sidebar-footer">
          <NavLink to="/avisos" className="notices tone-lilac" aria-label={unread > 0 ? `Avisos, ${unread} não lidos` : "Avisos"}>
            <IconBell size={18} aria-hidden="true" /> <span className="label">Avisos</span>
            {unread > 0 && <span className="badge" aria-hidden="true">{unread > 9 ? "9+" : unread}</span>}
          </NavLink>
          <span className="who">{session.data?.email}</span>
          <button type="button" className="ghost small" onClick={() => signOut()}>
            <IconLogout size={16} aria-hidden="true" /> Sair
          </button>
        </div>
      </aside>
      <main className="content">{children}</main>
    </div>
  );
}
