import express from "express"
import { invoices } from "./api/invoices.js"
import { auth } from "./api/auth.js"

const app = express()
app.use(express.json())

app.get("/health", (_req, res) => res.json({ status: "ok" }))
app.use(auth)
app.use(invoices)

const port = Number(process.env.PORT ?? 3000)
app.listen(port, () => console.log(`API en écoute sur :${port}`))
