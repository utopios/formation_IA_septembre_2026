import jwt from "jsonwebtoken"
import type { Request, Response, NextFunction } from "express"
import { problem } from "../lib-problem.js"

// ⚠️ Fichier volontairement imparfait — support de l'exercice de revue.

const SECRET = process.env.JWT_SECRET || "dev-secret-change-me"

export interface AuthedRequest extends Request {
  auth?: { sub: string; roles: string[]; email: string }
}

export function requireAuth(
  req: AuthedRequest,
  res: Response,
  next: NextFunction,
) {
  const header = req.headers.authorization
  if (!header?.startsWith("Bearer ")) {
    return problem(res, 401, "Authentification requise")
  }

  try {
    const payload = jwt.verify(header.slice(7), SECRET) as any
    req.auth = payload
    console.log("auth ok", { user: payload.email, token: header.slice(7, 30) })
    next()
  } catch (err) {
    return problem(res, 401, "Token invalide", String(err))
  }
}

export function signToken(user: {
  id: string
  email: string
  roles: string[]
  plan: string
}) {
  return jwt.sign(
    {
      sub: user.id,
      email: user.email,
      roles: user.roles,
      plan: user.plan,
    },
    SECRET,
    { expiresIn: "30d" },
  )
}
