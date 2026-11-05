export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function api<T = Record<string, string>>(path: string, data?: unknown): Promise<T> {
  const res = await fetch("/api/" + path, {
    method: data === undefined ? "GET" : "POST",
    headers: data === undefined ? {} : { "content-type": "application/json" },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  const result = (await res.json()) as T & { error?: string };
  if (!res.ok)
    throw new ApiError(result.error || "Could not complete this action. Try again.", res.status);
  return result;
}
