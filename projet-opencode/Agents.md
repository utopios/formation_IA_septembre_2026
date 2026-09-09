## Contexte

API de facturation interne. Express + TypeScript + PostgreSQL.
Les données manipulées sont des données personnelles et financières :
le niveau d'exigence sécurité est élevé.

## Commandes

```bash
npm run test        
npm run lint        
npm run typecheck  
```

## Conventions non négociables

- Français pour les commentaires, les messages de commit et la doc.
- Anglais pour les identifiants de code.
- Aucun `any` en TypeScript. Utiliser `unknown` et affiner.
- Aucun `console.log` en dehors des scripts. Utiliser `logger`.
- Les secrets viennent de l'environnement, jamais du code.

## Ce qu'un agent ne fait jamais

- `git push`, `git rebase`, création de tag ou de release.
- Modifier ou lire les fichiers `.env*`.
- Appliquer une migration de base de données.
- Désactiver ou supprimer un test pour faire passer la suite.