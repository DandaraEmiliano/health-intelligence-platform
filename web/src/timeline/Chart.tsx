import type { SeriesPoint } from "../api/resources";
import { formatDate } from "../ui/format";
import { formatDecimal } from "../exams/values";

const W = 640;
const H = 240;
const PAD = { left: 48, right: 16, top: 16, bottom: 36 };

/**
 * Values over time for one unit. Reference bounds are drawn per point, as printed in each report
 * (laboratories differ). Censored values ("< 0,5") are hollow: they are bounds, not exact points.
 */
export function SeriesChart({ points, unit }: { points: SeriesPoint[]; unit: string | null }) {
  const numeric = points.filter((p) => p.value !== undefined);
  if (numeric.length === 0) return null;
  const times = numeric.map((p) => Date.parse(p.collectedAt));
  const values = numeric.map((p) => Number(p.value));
  const bounds = numeric.flatMap((p) => [p.referenceRange.low, p.referenceRange.high].filter((v): v is string => v !== null).map(Number));
  let min = Math.min(...values, ...bounds);
  let max = Math.max(...values, ...bounds);
  if (min === max) {
    min -= 1;
    max += 1;
  }
  const margin = (max - min) * 0.1;
  min -= margin;
  max += margin;
  const t0 = Math.min(...times);
  const t1 = Math.max(...times);
  // Inner margin so the first and last points (and their reference bands) are not cut at the edges.
  const INSET = 28;
  const x = (t: number) =>
    t1 === t0 ? (W + PAD.left - PAD.right) / 2 : PAD.left + INSET + ((t - t0) / (t1 - t0)) * (W - PAD.left - PAD.right - 2 * INSET);
  const y = (v: number) => PAD.top + (1 - (v - min) / (max - min)) * (H - PAD.top - PAD.bottom);
  const path = numeric.map((_, i) => `${i === 0 ? "M" : "L"}${x(times[i]).toFixed(1)},${y(values[i]).toFixed(1)}`).join(" ");
  const ticks = [min + margin, (min + max) / 2, max - margin];

  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Gráfico com ${numeric.length} resultados${unit ? ` em ${unit}` : ""}`}>
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="grid" />
            <text x={PAD.left - 6} y={y(t) + 4} textAnchor="end" className="axis">{formatDecimal(String(Number(t.toFixed(2))))}</text>
          </g>
        ))}
        {numeric.map((p, i) => {
          const low = p.referenceRange.low === null ? null : y(Number(p.referenceRange.low));
          const high = p.referenceRange.high === null ? null : y(Number(p.referenceRange.high));
          if (low === null && high === null) return null;
          return (
            <rect key={`r${p.resultId}`} className="band" x={x(times[i]) - 9} width={18}
              y={high ?? PAD.top} height={(low ?? H - PAD.bottom) - (high ?? PAD.top)} rx={4} />
          );
        })}
        <path d={path} className="line" />
        {numeric.map((p, i) => (
          <circle key={p.resultId} cx={x(times[i])} cy={y(values[i])} r={5}
            className={`dot${p.comparator ? " censored" : ""}${p.flag && p.flag.value !== "IN_RANGE" ? " flagged" : ""}`}>
            <title>{`${formatDate(p.collectedAt)}: ${p.comparator ?? ""}${formatDecimal(p.value)} ${unit ?? ""}`}</title>
          </circle>
        ))}
        {numeric.map((p, i) => (i === 0 || i === numeric.length - 1 || numeric.length <= 6) && (
          <text key={`d${p.resultId}`} x={x(times[i])} y={H - 12} className="axis"
            textAnchor={numeric.length > 1 && i === 0 ? "start" : numeric.length > 1 && i === numeric.length - 1 ? "end" : "middle"}>
            {formatDate(p.collectedAt)}
          </text>
        ))}
      </svg>
      <figcaption className="hint">Faixas sombreadas: referência impressa em cada laudo. Círculo vazado: valor informado como limite (por exemplo, “&lt; 0,5”).</figcaption>
    </figure>
  );
}

/** Tiny trend line for the overview cards. */
export function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return null;
  const w = 96;
  const h = 28;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const d = values.map((v, i) => `${i === 0 ? "M" : "L"}${((i / (values.length - 1)) * (w - 4) + 2).toFixed(1)},${(h - 3 - ((v - min) / span) * (h - 6)).toFixed(1)}`).join(" ");
  return (
    <svg className="sparkline" viewBox={`0 0 ${w} ${h}`} width={w} height={h} aria-hidden="true">
      <path d={d} />
    </svg>
  );
}
