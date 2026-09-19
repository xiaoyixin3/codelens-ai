# Review guidance

- Do not make external network calls inside a database transaction.
- Payment mutations must be safe to retry.
- Never write credentials or customer payment details to logs.
