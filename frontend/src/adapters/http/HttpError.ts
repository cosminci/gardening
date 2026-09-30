// Coerce an unknown thrown value to an Error, wrapping non-Error rejections in
// one that carries the caller's fallback message.
export const coerceToError = (error: unknown, fallbackMessage: string): Error =>
  error instanceof Error ? error : new Error(fallbackMessage);
