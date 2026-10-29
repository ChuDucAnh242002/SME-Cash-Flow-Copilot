let csrf: { headerName: string; token: string } | null = null;

export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (method !== "GET" && !csrf) csrf = await request("/api/csrf");
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body && !(body instanceof FormData))
    headers["Content-Type"] = "application/json";
  if (method !== "GET" && csrf) headers[csrf.headerName] = csrf.token;
  const response = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body:
      body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    if (response.status === 401)
      throw new Error("Authentication expired. Reload and sign in again.");
    if (response.status === 403) {
      csrf = null;
      throw new Error(
        "The session security token expired. Please retry the action.",
      );
    }
    const problem: { detail?: string } = await response
      .json()
      .catch(() => ({}));
    throw new Error(
      problem.detail ??
        `Request failed (HTTP ${response.status}). Please retry.`,
    );
  }
  if (response.status === 204 || response.headers.get("content-length") === "0")
    return undefined as T;
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
