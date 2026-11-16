// Browser transport: only same-origin File Storage logical GET routes.
export const TENANT = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const routes = {
  overview: '/v1/usage', files: '/v1/files', deleted: '/v1/deleted-files',
  backends: '/v1/admin/backends', policies: '/v1/admin/policies/active'
};
export class ApiError extends Error {
  constructor(status, correlation = '') { super('File Storage request failed'); this.status = status; this.correlation = correlation; }
}
export function client({ token, tenant, preview = false, fetcher = fetch }) {
  if (!TENANT.test(tenant)) throw new Error('Invalid tenant');
  if (!preview && (!token || token.length > 8192 || /\s/.test(token))) throw new Error('A valid access token is required');
  async function get(path, query = {}, signal) {
    const url = new URL(path, 'http://local.invalid');
    if (url.origin !== 'http://local.invalid' || !allowedPath(url.pathname)) throw new Error('Unsupported route');
    for (const [key, value] of Object.entries(query)) if (value !== null && value !== undefined) url.searchParams.set(key, String(value));
    const headers = { Accept: 'application/json', 'X-Correlation-Id': globalThis.crypto.randomUUID() };
    if (!url.pathname.startsWith('/v1/admin/backends')) headers['X-Tenant-Id'] = tenant;
    if (!preview) headers.Authorization = 'Bearer ' + token;
    const res = await fetcher('/api' + url.pathname + url.search, { method:'GET', headers, signal, credentials:'omit', cache:'no-store', redirect:'error' });
    if (!res.ok) {
      const correlation = res.headers.get('X-Correlation-Id') || '';
      throw new ApiError(res.status, UUID.test(correlation) ? correlation : '');
    }
    if (!res.headers.get('content-type')?.includes('application/json')) throw new ApiError(502);
    return res.json();
  }
  return { get };
}
export function allowedPath(path) {
  return Object.values(routes).includes(path) ||
    /^\/v1\/admin\/backends\/[0-9a-f-]{36}\/locations$/i.test(path);
}
export function projectFile(row) {
  return [String(row.name ?? ''), String(row.versionState ?? ''), String(row.declaredSize ?? ''), String(row.declaredMimeType ?? ''), String(row.file?.fileId ?? '')];
}
export function projectBackend(row) {
  return [String(row.provider ?? ''), String(row.backendId ?? ''), String(row.latestRevision ?? ''), String(row.activeRevision ?? '—')];
}
export function projectLocation(row) { return [String(row.locationId ?? ''), String(row.backendId ?? '')]; }
export function projectPolicy(row) {
  return (row.rules ?? []).map(rule => [String(rule.locationId ?? ''), String(rule.priority ?? ''), rule.eligible === true ? 'Yes' : 'No']);
}
