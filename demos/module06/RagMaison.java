package fr.utopios.formation.module06;

import fr.utopios.formation.commun.Llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


public class RagMaison {

    /** Base documentaire d'exemple (politique interne fictive). */
    static final List<String> DOCUMENTS = List.of(
            "La politique de télétravail autorise jusqu'à 3 jours par semaine à distance.",
            "Les notes de frais doivent être soumises avant le 5 du mois suivant.",
            "Le code de conduite interdit tout harcèlement et garantit l'égalité de traitement.",
            "Les congés payés s'acquièrent à raison de 2,5 jours ouvrables par mois travaillé.",
            "La salle de réunion 'Mont Blanc' se réserve via l'intranet, 30 min minimum.");

    /** Un resultat de recherche : le score cosinus et le document. */
    record Resultat(double score, String document) { }

    private final List<String> documents;
    private final List<double[]> vecteurs = new ArrayList<>();

    /** INDEXATION : embeddings de tous les documents (prefixe search_document). */
    RagMaison(List<String> documents) {
        this.documents = documents;
        System.out.println("[index] calcul des embeddings de " + documents.size()
                + " documents...");
        for (String doc : documents) {
            vecteurs.add(Llm.embedDocument(doc));   // nomic est ASYMETRIQUE
        }
    }

    /** RECUPERATION : top-k par similarite cosinus contre la question. */
    List<Resultat> rechercher(String question, int k) {
        double[] q = Llm.embedQuery(question);      // prefixe search_query
        List<Resultat> resultats = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            resultats.add(new Resultat(Llm.cosine(vecteurs.get(i), q), documents.get(i)));
        }
        resultats.sort(Comparator.comparingDouble(Resultat::score).reversed());
        return resultats.subList(0, Math.min(k, resultats.size()));
    }

    /** GENERATION : les meilleurs chunks sont injectes dans le prompt. */
    String repondre(String question, int k) {
        List<Resultat> contexte = rechercher(question, k);
        System.out.println("\n[récup] top-" + k + " chunks pour : \"" + question + "\"");
        StringBuilder bloc = new StringBuilder();
        for (Resultat r : contexte) {
            System.out.printf("   (%.3f) %s%n", r.score(), r.document());
            bloc.append("- ").append(r.document()).append('\n');
        }
        String prompt = "Tu es un assistant interne. Le CONTEXTE ci-dessous provient de "
                + "documents internes officiels et fiables : tu peux t'y fier. "
                + "Réponds à la question en t'appuyant UNIQUEMENT sur ce contexte, de "
                + "façon directe. Si l'information n'y figure pas, dis-le simplement.\n\n"
                + "CONTEXTE :\n" + bloc + "\nQUESTION : " + question + "\nRÉPONSE :";
        // Coeur local : generation via Ollama, temperature 0.
        return Llm.chat(prompt, null, 0.0).strip();
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("MODULE 6 — RAG maison local (embeddings Ollama + cosinus Java)");
        System.out.println("=".repeat(70));
        RagMaison rag = new RagMaison(DOCUMENTS);

        for (String q : List.of("Combien de jours de télétravail sont autorisés ?",
                "Comment réserver une salle de réunion ?")) {
            String reponse = rag.repondre(q, 2);
            System.out.println("\n=> RÉPONSE : " + reponse);
            System.out.println("-".repeat(70));
        }

        System.out.println("\nNote pédagogique : la RÉCUPÉRATION (cœur du RAG) est fiable — observez");
        System.out.println("les bons chunks remontés avec un score cosinus élevé. La qualité de la");
        System.out.println("GÉNÉRATION dépend du modèle : un modèle 1b peut paraphraser ou se tromper");
        System.out.println("sur un chiffre ; un modèle plus gros rend une réponse plus fidèle au");
        System.out.println("contexte. Le RAG fournit le bon contexte ; le modèle le rédige.");
    }
}
