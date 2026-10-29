export type Kind = "INVOICE" | "EXPENSE";
export interface Document {
  id: string;
  kind: Kind;
  counterparty: string;
  reference: string;
  amount: number;
  dueDate: string;
  paidAmount: number;
  expectedDate: string | null;
  cancelled: boolean;
  revision: number;
}
export interface Transaction {
  accountId: string;
  externalId: string;
  bookingDate: string;
  amount: number;
  reference: string;
  counterparty: string;
  documentId: string | null;
}
export interface ForecastDay {
  date: string;
  balance: number;
  items: { documentId: string; label: string; amount: number }[];
}
export interface Dashboard {
  status: {
    mode: "demo" | "nordea";
    lastSync: string | null;
    syncError: string | null;
    lastReconciled: string | null;
    pendingEvents: number;
    eventError: string | null;
  };
  account: {
    id: string;
    name: string;
    currency: string;
    bookedBalance: number;
    observedAt: string;
  } | null;
  documents: Document[];
  planningBalance: number | null;
  transactions: Transaction[];
  forecast: {
    days: number;
    incomeDelayDays: number;
    openingBalance: number;
    minimumBalance: number;
    firstShortfall: string | null;
    series: ForecastDay[];
    assumptions: string[];
  };
}
