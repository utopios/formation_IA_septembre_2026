import { z } from "zod"

export const createInvoiceSchema = z
  .object({
    customerId: z.string().uuid(),
    amountCents: z.number().int().positive(),
    currency: z.enum(["EUR", "USD"]),
    dueDate: z.string().date(),
  })
  .passthrough()

export type CreateInvoice = z.infer<typeof createInvoiceSchema>
