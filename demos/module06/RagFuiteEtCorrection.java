package fr.utopios.formation.module06;

import fr.utopios.formation.commun.Llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


public class RagFuiteEtCorrection {

    /** Un morceau de document + ses METADONNEES d'autorisation. */
    static final class Chunk {
        final String texte;
        final Set<String> servicesAutorises;   // qui a le droit de VOIR ce chunk
        final boolean sensible;                // marqueur pedagogique pour la verification
        double[] vecteur;                      // rempli a l'indexation

        Chunk(String texte, Set<String> servicesAutorises, boolean sensible) {
            this.texte = texte;
            this.servicesAutorises = servicesAutorises;
            this.sensible = sensible;
        }
    }

    /** Base melangeant RH (confidentiel) et Commercial (diffusable). */
    static final List<Chunk> CHUNKS = List.of(
            // --- RH : CONFIDENTIEL, reserve au service RH ---
            new Chunk("Salaire annuel brut de Jean Martin (développeur senior) : 72 000 €.",
                    Set.of("RH"), true),
            new Chunk("Dossier disciplinaire : avertissement adressé à Sophie Durand en mars.",
                    Set.of("RH"), true),
            new Chunk("Grille des augmentations RH 2026 : enveloppe globale de 3,2 %.",
                    Set.of("RH"), true),
            // --- Commercial : diffusable au service Commercial ---
            new Chunk("Le tarif catalogue du produit Alpha est de 1 200 € HT.",
                    Set.of("Commercial"), false),
            new Chunk("Remise commerciale maximale autorisée pour un grand compte : 15 %.",
                    Set.of("Commercial"), false),
            new Chunk("Le cycle de vente moyen pour l'offre Entreprise est de 45 jours.",
                    Set.of("Commercial"), false),
            // --- Document partage (les deux services) ---
            new Chunk("La cantine d'entreprise est ouverte de 12h00 à 14h00.",
                    Set.of("RH", "Commercial"), false));

    /** Calcule et stocke les embeddings (prefixe search_document). */
    static void construireIndex(List<Chunk> chunks) {
        System.out.println("[index] embeddings de " + chunks.size()
                + " chunks (RH + Commercial mélangés)...");
        for (Chunk c : chunks) {
            c.vecteur = Llm.embedDocument(c.texte);
        }
    }

    /** Une paire (score cosinus, chunk). */
    record Score(double valeur, Chunk chunk) { }

    /** Tous les chunks candidats, classes par similarite decroissante. */
    static List<Score> scores(String question, List<Chunk> chunks) {
        double[] q = Llm.embedQuery(question);      // prefixe search_query
        List<Score> paires = new ArrayList<>();
        for (Chunk c : chunks) {
            paires.add(new Score(Llm.cosine(c.vecteur, q), c));
        }
        paires.sort(Comparator.comparingDouble(Score::valeur).reversed());
        return paires;
    }

    // ----------------------------------------------------------------------
    // 1) Recuperation NAIVE — ignore les permissions (VULNERABLE)
    // ----------------------------------------------------------------------
    static List<Chunk> recupererNaif(String question, List<Chunk> chunks, int k) {
        return scores(question, chunks).stream()
                .limit(k)
                .map(Score::chunk)
                .toList();
    }

    // ----------------------------------------------------------------------
    // 2) Recuperation SECURISEE — filtre par permissions AVANT le top-k
    // ----------------------------------------------------------------------
    static List<Chunk> recupererSecurise(String question, List<Chunk> chunks,
                                         String serviceUtilisateur, int k) {
        // Principe du besoin d'en connaitre : on ne considere QUE les chunks
        // autorises pour le service de l'utilisateur, PUIS on classe.
        List<Chunk> autorises = chunks.stream()
                .filter(c -> c.servicesAutorises.contains(serviceUtilisateur))
                .toList();
        return scores(question, autorises).stream()
                .limit(k)
                .map(Score::chunk)
                .toList();
    }

    /** Affiche les chunks recuperes et renvoie le nb de chunks SENSIBLES fuites. */
    static int afficher(String titre, List<Chunk> chunks) {
        System.out.println("\n  " + titre);
        int fuites = 0;
        for (Chunk c : chunks) {
            String tag = c.sensible ? "  <<< SENSIBLE RH" : "";
            String services = String.join("/", new TreeSet<>(c.servicesAutorises));
            System.out.printf("    [%16s] %s%s%n", services, c.texte, tag);
            if (c.sensible) {
                fuites++;
            }
        }
        return fuites;
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("MODULE 6 — Fuite inter-services dans un RAG partagé, et sa correction");
        System.out.println("=".repeat(70));
        construireIndex(CHUNKS);

        // L'utilisateur est du COMMERCIAL et pose une question qui, semantiquement,
        // est proche de contenus RH sensibles (remuneration).
        String service = "Commercial";
        String question = "Quelles sont les informations sur les rémunérations et "
                + "augmentations de salaire ?";
        System.out.println("\nUtilisateur du service : " + service);
        System.out.println("Question posée         : \"" + question + "\"");

        // --- CAS 1 : RAG NAIF (sans controle d'acces) ---
        System.out.println("\n" + "-".repeat(70));
        System.out.println("CAS 1 — RAG NAÏF (la récupération ignore les permissions)");
        System.out.println("-".repeat(70));
        List<Chunk> naif = recupererNaif(question, CHUNKS, 3);
        int fuitesNaif = afficher(
                "Chunks renvoyés au LLM (puis à l'utilisateur Commercial) :", naif);
        System.out.println("\n  >>> " + fuitesNaif
                + " chunk(s) RH SENSIBLE(S) ont fuité vers le Commercial !");

        // --- CAS 2 : RAG SECURISE (filtrage par permissions) ---
        System.out.println("\n" + "-".repeat(70));
        System.out.println("CAS 2 — RAG SÉCURISÉ (filtrage par métadonnées d'autorisation)");
        System.out.println("-".repeat(70));
        List<Chunk> secur = recupererSecurise(question, CHUNKS, service, 3);
        int fuitesSecur = afficher(
                "Chunks renvoyés au LLM (après filtrage par rôle) :", secur);
        System.out.println("\n  >>> " + fuitesSecur
                + " chunk(s) RH sensible(s) renvoyé(s) au Commercial.");

        // --- VERIFICATION automatique de la demo (equivalent des assertions) ---
        System.out.println("\n" + "=".repeat(70));
        System.out.println("VÉRIFICATION");
        System.out.println("=".repeat(70));
        System.out.println("  Fuite RH dans le RAG NAÏF     : " + fuitesNaif
                + " chunk(s) sensible(s)");
        System.out.println("  Fuite RH dans le RAG SÉCURISÉ : " + fuitesSecur
                + " chunk(s) sensible(s)");

        if (fuitesNaif == 0) {
            throw new IllegalStateException("La démo devait PROVOQUER une fuite dans le "
                    + "cas naïf (aucun chunk sensible remonté).");
        }
        if (fuitesSecur > 0) {
            throw new IllegalStateException("Le filtrage par permissions devait SUPPRIMER "
                    + "toute fuite (un chunk sensible a quand même fuité).");
        }
        System.out.println("\n  RÉSULTAT : la fuite EXISTE sans contrôle d'accès, et DISPARAÎT");
        System.out.println("  après filtrage par permissions (métadonnées d'autorisation + besoin");
        System.out.println("  d'en connaître). C'est la contre-mesure centrale d'un RAG d'entreprise.");
        System.out.println("\n  Pour aller plus loin : cloisonner les index par service (un index");
        System.out.println("  par périmètre), RBAC à la récupération, audit/traçabilité des accès.");
    }
}
