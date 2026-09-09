import type { Response } from "express"

/** Helper d'erreur RFC7807 imposé par les conventions de l'équipe. */
export function problem(
  res: Response,
  status: number,
  title: string,
  detail?: string,
) {
  return res.status(status).type("application/problem+json").json({
    type: `https://api.interne/errors/${status}`,
    title,
    status,
    detail,
  })
}

export function maskEmail(email: string): string {
  const [local, domain] = email.split("@")
  if (!local || !domain) return "***"
  return `${local[0]}***@${domain}`
}
