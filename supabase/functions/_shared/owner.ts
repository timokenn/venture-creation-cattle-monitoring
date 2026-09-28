import { serviceClient } from "./db.ts";

/**
 * Resolve the calling user's auth.users id from the request's Authorization
 * header (the app sends its Supabase Auth access token as a Bearer JWT).
 * Returns null when the header is missing or the token does not verify.
 */
export async function requireOwner(req: Request): Promise<string | null> {
  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) return null;
  const { data, error } = await serviceClient().auth.getUser(token);
  if (error) return null;
  return data.user?.id ?? null;
}
