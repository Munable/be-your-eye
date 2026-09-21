declare module "@be-your-eyes/catalog-validator/src/index.mjs" {
  export interface ValidationIssue { path: string; code: string; message: unknown }
  export interface ValidationResult { ok: boolean; errors: ValidationIssue[] }
  export function validateContract(kind: string, value: unknown, context?: Record<string, unknown>): ValidationResult;
}
