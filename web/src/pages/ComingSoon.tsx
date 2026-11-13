export interface ComingSoonProps {
  title: string;
  phase: number;
  description: string;
}

/** Placeholder for a feature that is planned but not built yet. Shows no fake data. */
export function ComingSoon({ title, phase, description }: ComingSoonProps) {
  return (
    <section>
      <h1>{title}</h1>
      <p>{description}</p>
      <p className="muted">Planejado para a Fase {phase}.</p>
    </section>
  );
}
