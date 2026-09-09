/** Stub de base de données — le projet n'a pas vocation à tourner. */
export const db = {
  async query(_sql: string, _params?: unknown[]): Promise<any> {
    throw new Error("stub: non implémenté")
  },
  async insert(_table: string, _data: unknown): Promise<any> {
    throw new Error("stub: non implémenté")
  },
}
