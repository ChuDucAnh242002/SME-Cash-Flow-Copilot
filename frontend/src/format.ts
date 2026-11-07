export const money = (value: number) =>
  new Intl.NumberFormat("en-FI", {
    style: "currency",
    currency: "EUR",
    maximumFractionDigits: 2,
  }).format(value);

export const date = (value: string) =>
  new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
  }).format(new Date(`${value}T12:00:00`));

export const timestamp = (value: string) =>
  `${new Date(value).toLocaleString("en-GB", { timeZone: "Europe/Helsinki" })} Helsinki`;

export function localToday(): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Helsinki",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

export function documentState(amount: number, paid: number): string {
  if (paid >= amount) return "Settled";
  return paid > 0 ? "Partially paid" : "Open";
}
