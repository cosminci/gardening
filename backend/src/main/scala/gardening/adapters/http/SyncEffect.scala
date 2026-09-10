package gardening.adapters.http

/**
 * Direct-style ("identity") effect for the tapir sync interpreter: `F[A] = A`. Declared as a local transparent alias so
 * the HTTP layer need not depend on the interpreter's internal effect name; it unifies with tapir's own identity
 * effect.
 */
type Identity[A] = A
