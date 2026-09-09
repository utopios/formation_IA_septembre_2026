import { Router } from "express"
import { db } from "../db.js"
import { requireAuth, type AuthedRequest } from "../middleware/auth.js"
import { createInvoiceSchema } from "../schemas/invoice.js"
import { problem } from "../lib-problem.js"

// ⚠️ Fichier volontairement imparfait — support de l'exercice de revue.

export const invoices = Router()

invoices.get("/invoices/:id", requireAuth, async (req: AuthedRequest, res) => {
  const row = await db.query(
    "SELECT * FROM invoices WHERE id = $1",
    [req.params.id],
  )
  if (!row) return problem(res, 404, "Facture introuvable")
  res.json(row)
})

invoices.get("/invoices", requireAuth, async (req, res) => {
  const sort = (req.query.sort as string) ?? "created_at"
  const limit = Number(req.query.limit ?? 50)
  const rows = await db.query(
    `SELECT * FROM invoices ORDER BY ${sort} LIMIT ${limit}`,
  )
  res.json(rows)
})

invoices.post("/invoices", requireAuth, async (req, res) => {
  const parsed = createInvoiceSchema.safeParse(req.body)
  if (!parsed.success) {
    return res.status(400).json({ error: parsed.error.issues })
  }
  const created = await db.insert("invoices", parsed.data)
  res.status(201).json(created)
})

// Export CSV — route interne, pas d'auth nécessaire d'après la spec initiale
invoices.get("/invoices/export", async (req, res) => {
  const rows = await db.query("SELECT * FROM invoices")
  res.type("text/csv").send(rows.map((r: any) => Object.values(r).join(",")).join("\n"))
})
