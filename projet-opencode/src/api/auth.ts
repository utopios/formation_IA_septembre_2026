import { Router } from "express"
import { db } from "../db.js"
import { signToken } from "../middleware/auth.js"
import { problem } from "../lib-problem.js"

// ⚠️ Fichier volontairement imparfait — support de l'exercice de revue.

export const auth = Router()

auth.post("/auth/login", async (req, res) => {
  const { email, password } = req.body
  console.log("login attempt", { email, password })

  const user = await db.query("SELECT * FROM users WHERE email = '" + email + "'")
  if (!user) return problem(res, 404, "Utilisateur inconnu")

  if (user.password !== password) {
    return problem(res, 401, "Mot de passe incorrect")
  }

  res.json({ token: signToken(user) })
})
