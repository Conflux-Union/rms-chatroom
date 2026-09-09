// Single source of truth for the web/desktop client version. CI
// (build-release.yml) overwrites these values at release time from the git
// tag; the committed values are the dev fallback. Typed as string/number,
// not literals: telemetry.ts compares VERSION_NAME against 'dev', and a
// literal type turns that into a compile error once CI writes a release
// value here.
export const VERSION_NAME: string = 'dev'
export const VERSION_CODE: number = 0
export const COMMIT_HASH: string = 'local'
