package fr.utopios.formation.solutions;

import fr.utopios.formation.commun.Llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


public class Tp06 {

    // ======================================================================
    // ETAPE 1 — RAG maison complet avec citations [source N]
    // ======================================================================

    static final List<String> DOCUMENTS = List.of(
            "L'export CSV des données se lance depuis Paramètres puis Exporter (format UTF-8).",
            "La réinitialisation d'un mot de passe utilisateur se fait par l'administrateur du compte.",
            "Les sauvegardes de la plateforme sont quotidiennes et conservées 30 jours.",
            "L'API REST est limitée à 1 000 requêtes par heure et par clé.",
            "Le support est joignable du lundi au vendredi, de 8h à 19h.");

    /** Un resultat de recherche : score cosinus, numero de source, document. */
    record Resultat(double score, int source, String document) { }

    static List<double[]> indexerDocuments(List<String> documents) {
        List<double[]> vecteurs = new ArrayList<>();
        for (String doc : documents) {
            vecteurs.add(Llm.embedDocument(doc));   // prefixe search_document
        }
        return vecteurs;
    }

    static List<Resultat> rechercher(String question, List<String> documents,
                                     List<double[]> vecteurs, int k) {
        double[] q = Llm.embedQuery(question);      // prefixe search_query
        List<Resultat> resultats = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            resultats.add(new Resultat(Llm.cosine(vecteurs.get(i), q), i, documents.get(i)));
        }
        resultats.sort(Comparator.comparingDouble(Resultat::score).reversed());
        return resultats.subList(0, Math.min(k, resultats.size()));
    }

    static String repondre(String question, List<String> documents,
                           List<double[]> vecteurs, int k) {
        List<Resultat> contexte = rechercher(question, documents, vecteurs, k);
        System.out.println("\n[récup] top-" + k + " pour \"" + question + "\"");
        StringBuilder bloc = new StringBuilder();
        for (Resultat r : contexte) {
            System.out.printf("   [source %d] (%.3f) %s%n", r.source(), r.score(), r.document());
            bloc.append("[source ").append(r.source()).append("] ")
                    .append(r.document()).append('\n');
        }
        // Formulation calibree pour un 1b : la consigne de citation en fin de
        // reponse ([source N]) est celle qu'il suit le plus fidelement.
        String prompt = "Tu es un assistant interne. Le CONTEXTE ci-dessous provient de "
                + "documents internes officiels et fiables : tu peux t'y fier. "
                + "Réponds à la question en t'appuyant UNIQUEMENT sur ce contexte, de "
                + "façon directe. Termine OBLIGATOIREMENT ta réponse par le numéro de "
                + "la source utilisée, sous la forme [source N]. Si l'information n'y "
                + "figure pas, dis-le simplement.\n\n"
                + "CONTEXTE :\n" + bloc + "\nQUESTION : " + question + "\nRÉPONSE :";
        return Llm.chat(prompt, null, 0.0).strip();
    }

    // ======================================================================
    // ETAPES 2 et 3 — La fuite inter-services et sa correction
    // ======================================================================

    /** Un chunk + ses METADONNEES d'autorisation. */
    static final class Chunk {
        final String texte;
        final Set<String> tenantsAutorises;    // quels CLIENTS ont le droit de VOIR ce chunk
        final boolean sensible;                // marqueur de verification
        double[] vecteur;

        Chunk(String texte, Set<String> tenantsAutorises, boolean sensible) {
            this.texte = texte;
            this.tenantsAutorises = tenantsAutorises;
            this.sensible = sensible;
        }
    }

    static final List<Chunk> CHUNKS = List.of(
            // Tenant ACME INDUSTRIE : CONFIDENTIEL CLIENT (contrat, incidents)
            new Chunk("Contrat Acme Industrie : remise négociée de 22 % sur l'abonnement Enterprise.",
                    Set.of("Acme"), true),
            new Chunk("Incident de sécurité du 12 mars chez Acme Industrie : fuite de mots de passe, correctif déployé.",
                    Set.of("Acme"), true),
            new Chunk("Configuration SSO d'Acme Industrie : annuaire Azure AD, domaine acme-industrie.fr.",
                    Set.of("Acme"), true),
            // Tenant BRICODIS : donnees du client BricoDis
            new Chunk("Contrat BricoDis : abonnement Standard au tarif catalogue, sans remise négociée.",
                    Set.of("BricoDis"), false),
            new Chunk("Configuration BricoDis : authentification LDAP interne, 45 utilisateurs.",
                    Set.of("BricoDis"), false),
            new Chunk("Ticket ouvert BricoDis : impression des bons de livraison, en attente du correctif.",
                    Set.of("BricoDis"), false),
            // Documentation produit PARTAGEE (tous les tenants)
            new Chunk("Les sauvegardes de la plateforme sont quotidiennes et conservées 30 jours.",
                    Set.of("Acme", "BricoDis"), false));

    static void construireIndex(List<Chunk> chunks) {
        for (Chunk c : chunks) {
            c.vecteur = Llm.embedDocument(c.texte);
        }
    }

    record Score(double valeur, Chunk chunk) { }

    static List<Score> scores(String question, List<Chunk> chunks) {
        double[] q = Llm.embedQuery(question);
        List<Score> paires = new ArrayList<>();
        for (Chunk c : chunks) {
            paires.add(new Score(Llm.cosine(c.vecteur, q), c));
        }
        paires.sort(Comparator.comparingDouble(Score::valeur).reversed());
        return paires;
    }

    /** Recuperation NAIVE : ignore les permissions -> VULNERABLE. */
    static List<Chunk> recupererNaif(String question, List<Chunk> chunks, int k) {
        return scores(question, chunks).stream().limit(k).map(Score::chunk).toList();
    }

    /** Recuperation SECURISEE : on filtre par TENANT, PUIS on classe. */
    static List<Chunk> recupererSecurise(String question, List<Chunk> chunks,
                                         String tenantUtilisateur, int k) {
        List<Chunk> autorises = chunks.stream()
                .filter(c -> c.tenantsAutorises.contains(tenantUtilisateur))
                .toList();
        return scores(question, autorises).stream().limit(k).map(Score::chunk).toList();
    }

    static int afficher(String titre, List<Chunk> chunks) {
        System.out.println("\n  " + titre);
        int fuites = 0;
        for (Chunk c : chunks) {
            String tag = c.sensible ? "  <<< CONFIDENTIEL CLIENT TIERS" : "";
            String tenants = String.join("/", new TreeSet<>(c.tenantsAutorises));
            System.out.printf("    [%14s] %s%s%n", tenants, c.texte, tag);
            if (c.sensible) {
                fuites++;
            }
        }
        return fuites;
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("TP 06 (solution) — RAG complet et cloisonné : fuite puis correction");
        System.out.println("=".repeat(70));

        // --- Etape 1 : RAG maison avec citations --------------------------
        System.out.println("\n### Étape 1 — RAG maison avec citations [source N] ###");
        System.out.println("[index] embeddings de " + DOCUMENTS.size() + " documents...");
        List<double[]> vecteurs = indexerDocuments(DOCUMENTS);
        for (String q : List.of("Comment exporter mes données en CSV ?",
                "Combien de temps les sauvegardes sont-elles conservées ?")) {
            System.out.println("\n=> RÉPONSE : " + repondre(q, DOCUMENTS, vecteurs, 2));
        }

        // --- Etape 2 : reproduire la fuite --------------------------------
        System.out.println("\n### Étape 2 — Reproduire la fuite inter-clients (multi-tenants) ###");
        System.out.println("[index] embeddings de " + CHUNKS.size()
                + " chunks (tenants Acme + BricoDis + doc partagée MÉLANGÉS)...");
        construireIndex(CHUNKS);
        String tenant = "BricoDis";
        String question = "Quelle remise a été négociée sur le contrat, et y a-t-il eu "
                + "des incidents de sécurité ?";
        System.out.println("Agent support répondant au client : " + tenant);
        System.out.println("Question                          : \"" + question + "\"");

        List<Chunk> naif = recupererNaif(question, CHUNKS, 3);
        int fuitesNaif = afficher("CAS 1 — RAG NAÏF (renvoyé côté BricoDis) :", naif);
        System.out.println("\n  >>> " + fuitesNaif
                + " chunk(s) CONFIDENTIEL(S) d'Acme ont fuité vers BricoDis !");

        // --- Etape 3 : corriger et VERIFIER -------------------------------
        System.out.println("\n### Étape 3 — Corriger (filtrage AVANT le top-k) et vérifier ###");
        List<Chunk> secur = recupererSecurise(question, CHUNKS, tenant, 3);
        int fuitesSecur = afficher("CAS 2 — RAG SÉCURISÉ (après filtrage par tenant) :", secur);
        System.out.println("\n  >>> " + fuitesSecur
                + " chunk(s) confidentiel(s) d'Acme renvoyé(s) à BricoDis.");

        // Verification programmatique : la correction est PROUVEE, pas affirmee.
        if (fuitesNaif == 0) {
            throw new IllegalStateException(
                    "La démo devait PROVOQUER une fuite dans le cas naïf.");
        }
        if (fuitesSecur > 0) {
            throw new IllegalStateException(
                    "Le filtrage devait SUPPRIMER toute fuite.");
        }
        System.out.println("\n  RÉSULTAT : fuite SANS contrôle d'accès ("
                + fuitesNaif + " chunks), ZÉRO fuite après filtrage.");
        System.out.println("  Pourquoi filtrer AVANT le top-k : (a) sinon le chunk confidentiel");
        System.out.println("  est déjà extrait de l'index côté serveur, (b) le top-k peut se");
        System.out.println("  retrouver vide, (c) des chunks interdits évincent les légitimes.");

        // --- Etape 4 : contre-mesures complementaires ---------------------
        System.out.println("""

                ### Étape 4 — Contre-mesures complémentaires ###
                 - Cloisonnement des index : un index PAR TENANT pour les clients très
                   sensibles (défense en profondeur : un bug de filtre n'expose qu'un
                   client). C'est LE risque n°1 des RAG de SaaS multi-tenants.
                 - Le tenant vient de l'AUTHENTIFICATION (session du portail client),
                   JAMAIS d'un paramètre fourni par l'appelant. Ici, tenantUtilisateur
                   est un paramètre de démo ; en production il dérive du token.
                 - Citations + traçabilité : journaliser quel chunk a été renvoyé à qui.
                 - Échelle : sur des millions de chunks, le filtrage par métadonnées doit
                   s'exécuter NATIVEMENT dans le vector store à la requête, pas en Java
                   après coup.
                 - Ne JAMAIS confier la confidentialité au prompt : le LLM n'est pas un
                   mécanisme d'autorisation.""");
    }
}
