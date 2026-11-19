import type { NavItem } from "../navigation";
import { PageHeader } from "../ui/PageHeader";

/** Placeholder for a feature that is planned but not built yet. Shows no fake data. */
export function ComingSoon({ item }: { item: NavItem }) {
  return (
    <section className="stack">
      <PageHeader item={item} />
      <div className={`card tinted tone-${item.tone}`}>
        <p>Planejado para a Fase {item.plannedPhase}.</p>
      </div>
    </section>
  );
}
