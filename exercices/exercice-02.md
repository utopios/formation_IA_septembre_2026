# Exercice 02 — Estimer le coût d'un cas d'usage en tokens, avec et sans cache

> Module : 2 — Bases de l'IA générative textuelle
> Durée estimée : 35 min
> Difficulté : 2 / 5
> Type : Exercice d'application

## Objectifs pédagogiques

À la fin de cet exercice, vous serez capable de :

- Estimer le coût d'un cas d'usage LLM en tokens d'entrée et de sortie, puis en euros
- Quantifier l'économie apportée par le prompt caching sur un préfixe stable réutilisé

## Prérequis

- Avoir suivi les parties `Fenêtre de contexte` et `Prompt caching` du module 2
- Environnement : Python 3 (optionnel) ; un tableur ou une feuille de calcul à la main suffisent
- Outils : la table des tarifs 2026 fournie ci-dessous ; `client.messages.count_tokens(...)` si vous voulez vérifier des comptes réels

## Contexte

Vous concevez un assistant interne qui répond aux questions des employés à partir d'un manuel de procédures volumineux. À CHAQUE question, votre application envoie au modèle : (1) le manuel complet comme contexte de référence, puis (2) la question de l'employé. Le modèle renvoie une réponse.

Hypothèses chiffrées :
- Le manuel de référence (préfixe stable, identique à chaque appel) : 30 000 tokens.
- La question de l'employé (variable) : en moyenne 80 tokens.
- La réponse générée : en moyenne 300 tokens.
- Volume : 2 000 questions par jour.
- Modèle visé : Claude Opus 4.8.

Tarifs 2026 (Opus 4.8) à utiliser :
- Entrée : 5,00 $ / 1M tokens
- Sortie : 25,00 $ / 1M tokens
- Cache : lecture ≈ 0,1 × le prix d'entrée ; écriture ≈ 1,25 × le prix d'entrée ; TTL 5 min ; min cacheable Opus 4.8 = 4 096 tokens.

## Énoncé

### Partie 1 — Coût SANS prompt caching

Calculez, pour UNE question puis pour la journée entière (2 000 questions) :

- Le nombre de tokens d'entrée facturés par appel (manuel + question).
- Le nombre de tokens de sortie par appel.
- Le coût d'entrée, le coût de sortie, et le coût total par appel.
- Le coût total quotidien, puis mensuel (30 jours).

Résultat attendu : un calcul détaillé montrant le coût par appel et le coût mensuel sans cache.

### Partie 2 — Coût AVEC prompt caching

On met le manuel (30 000 tokens) en préfixe caché. Supposez, pour simplifier, qu'au sein de chaque tranche de 5 minutes le cache est écrit une fois puis lu pour toutes les questions suivantes de la tranche, et qu'il y a en moyenne 7 questions par tranche de 5 minutes.

Calculez :

- Le coût d'une écriture de cache (le manuel à ≈ 1,25 × le prix d'entrée).
- Le coût d'une lecture de cache (le manuel à ≈ 0,1 × le prix d'entrée).
- Le coût d'entrée de la partie NON cachée (la question, 80 tokens) à plein tarif.
- Le coût moyen par question dans une tranche (1 écriture + 6 lectures réparties sur 7 questions), puis le coût quotidien et mensuel.

Comparez au résultat de la Partie 1 : exprimez l'économie en pourcentage sur la partie entrée, et sur le coût total.

Résultat attendu : un calcul détaillé avec cache et le pourcentage d'économie par rapport à la Partie 1.

## Indices (à consulter si bloqué)

<details>
<summary>Indice 1</summary>

Convertissez d'abord chaque tarif en coût par token : 5,00 $ / 1 000 000 = 0,000005 $ par token d'entrée. Travaillez ensuite en multipliant les nombres de tokens par ce coût unitaire. La sortie est facturée pareil dans les deux scénarios — c'est l'ENTRÉE que le cache change.

</details>

<details>
<summary>Indice 2</summary>

Pour la Partie 2, raisonnez par tranche de 5 minutes : sur 7 questions, le manuel est écrit en cache une seule fois (≈ 1,25× pour 30 000 tokens) puis lu 6 fois (≈ 0,1× pour 30 000 tokens chacune). Additionnez, ajoutez la question à plein tarif pour les 7 appels et la sortie pour les 7 appels, puis divisez par 7 pour le coût moyen par question.

</details>

## Pour aller plus loin (bonus)

Refaites l'estimation pour Claude Haiku 4.5 (entrée 1,00 $/1M, sortie 5,00 $/1M) et comparez le coût mensuel total avec cache pour Opus vs Haiku. À quel moment l'écart de qualité justifierait-il le surcoût d'Opus ? Notez aussi ce que change le passage du manuel à 100 000 tokens (cas long contexte) sur l'intérêt relatif du cache.
