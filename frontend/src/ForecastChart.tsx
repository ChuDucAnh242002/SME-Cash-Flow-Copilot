import type { ForecastDay } from "./types";
import { date, money } from "./format";

export function ForecastChart({
  series,
  opening,
}: {
  series: ForecastDay[];
  opening: number;
}) {
  const values = [opening, ...series.map((day) => day.balance), 0];
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const y = (value: number) => 24 + ((max - value) / span) * 190;
  const x = (index: number) =>
    80 + (index / Math.max(series.length - 1, 1)) * 780;
  const points = series.map((day, i) => `${x(i)},${y(day.balance)}`).join(" ");
  const ticks = [max, (max + min) / 2, min];
  return (
    <svg
      className="forecast-chart"
      viewBox="0 0 900 265"
      role="img"
      aria-label={`Projected daily closing balance. Minimum ${money(Math.min(opening, ...series.map((day) => day.balance)))}. Detailed values in the forecast table below.`}
    >
      <defs>
        <linearGradient id="cash-fill" x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#278577" stopOpacity=".22" />
          <stop offset="100%" stopColor="#278577" stopOpacity=".02" />
        </linearGradient>
      </defs>
      {ticks.map((tick, i) => (
        <g key={i}>
          <line
            x1="80"
            y1={y(tick)}
            x2="860"
            y2={y(tick)}
            className="grid-line"
          />
          <text x="68" y={y(tick) + 4} textAnchor="end" className="axis-label">
            {Math.round(tick).toLocaleString("en-FI")}
          </text>
        </g>
      ))}
      <line x1="80" y1={y(0)} x2="860" y2={y(0)} className="zero-line" />
      {series.length > 0 && (
        <>
          <polygon
            points={`80,${y(min)} ${points} 860,${y(min)}`}
            fill="url(#cash-fill)"
          />
          <polyline
            points={points}
            fill="none"
            stroke="#1b7568"
            strokeWidth="3"
            strokeLinejoin="round"
          />
        </>
      )}
      {series.map(
        (day, i) =>
          day.items.length > 0 && (
            <circle
              key={day.date}
              cx={x(i)}
              cy={y(day.balance)}
              r="4"
              fill={day.balance < 0 ? "#c24a36" : "#1b7568"}
            >
              <title>
                {date(day.date)}: {money(day.balance)}
              </title>
            </circle>
          ),
      )}
      {[0, Math.floor(series.length / 2), series.length - 1].map(
        (index, i) =>
          series[index] && (
            <text
              key={i}
              x={x(index)}
              y="250"
              textAnchor="middle"
              className="axis-label"
            >
              {date(series[index].date)}
            </text>
          ),
      )}
    </svg>
  );
}
