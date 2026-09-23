import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * Service-role Supabase client for Edge Functions. The service key bypasses
 * RLS — it must never appear in the app or on a device.
 *
 * Hosted Supabase injects SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY into
 * every Edge Function automatically; SERVICE_ROLE_KEY is only needed when
 * testing functions locally outside that environment.
 */
export function serviceClient(): SupabaseClient {
  const url = Deno.env.get("SUPABASE_URL");
  const key =
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? Deno.env.get("SERVICE_ROLE_KEY");
  if (!url || !key) {
    throw new Error(
      "SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY must be set (they are auto-injected on hosted Supabase)",
    );
  }
  return createClient(url, key, { auth: { persistSession: false } });
}

export function webhookSecret(): string {
  const secret = Deno.env.get("WEBHOOK_SECRET");
  if (!secret) {
    throw new Error("WEBHOOK_SECRET secret must be set (supabase secrets set WEBHOOK_SECRET=...)");
  }
  return secret;
}
