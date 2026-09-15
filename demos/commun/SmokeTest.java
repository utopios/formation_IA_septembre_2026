package fr.utopios.formation.commun;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

/**
 * Test de fumee du socle Java de la formation.
 *
 * <p>Verifie que les deux chemins d'acces au LLM local fonctionnent :</p>
 * <ol>
 *   <li>Java pur (classe {@link Llm} : HttpClient + Jackson) — chat,
 *       embeddings, similarite cosinus ;</li>
 *   <li>LangChain4j (OllamaChatModel / OllamaEmbeddingModel) — utilise
 *       plus tard pour le RAG.</li>
 * </ol>
 *
 * <p>Lancement :</p>
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass="fr.utopios.formation.commun.SmokeTest"
 * </pre>
 */
public class SmokeTest {

    public static void main(String[] args) {
        System.out.println("=== Test de fumee du socle Java (Ollama local) ===");

        // 1. Chat en Java pur --------------------------------------------------
        System.out.println("\n[1/4] Chat (Java pur, modele " + Llm.modeleChat() + ")");
        String reponse = Llm.chat("Quelle est la capitale de la France ? Reponds en une phrase.",
                "Tu es un assistant concis qui repond en francais.", 0.0);
        System.out.println("Reponse : " + reponse.strip());
        verifier(!reponse.isBlank(), "la reponse du chat ne doit pas etre vide");

        // 2. Embedding en Java pur --------------------------------------------
        System.out.println("\n[2/4] Embedding (Java pur, modele " + Llm.MODELE_EMBEDDING + ")");
        double[] vecteur = Llm.embedDocument("Le chat dort sur le canape.");
        System.out.println("Dimension du vecteur : " + vecteur.length);
        verifier(vecteur.length == 768, "dimension attendue 768, obtenu " + vecteur.length);

        // 3. Similarite cosinus : proche vs eloignee --------------------------
        System.out.println("\n[3/4] Similarite cosinus (phrases proches vs eloignees)");
        double[] a = Llm.embedDocument("Le chat dort sur le canape.");
        double[] b = Llm.embedDocument("Le chat fait la sieste sur le canape du salon.");
        double[] c = Llm.embedDocument("La fusee decolle vers la station spatiale internationale.");
        double simProche = Llm.cosine(a, b);
        double simEloignee = Llm.cosine(a, c);
        System.out.printf("Proche   (chat qui dort / chat qui fait la sieste) : %.4f%n", simProche);
        System.out.printf("Eloignee (chat qui dort / fusee spatiale)          : %.4f%n", simEloignee);
        verifier(simProche > simEloignee,
                "la similarite proche doit depasser l'eloignee ("
                        + simProche + " <= " + simEloignee + ")");

        // 4. Chemin LangChain4j -----------------------------------------------
        System.out.println("\n[4/4] LangChain4j (OllamaChatModel + OllamaEmbeddingModel)");
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .baseUrl(Llm.BASE_URL)
                .modelName(Llm.modeleChat())
                .temperature(0.0)
                .build();
        String reponseL4j = chatModel.chat("Quelle est la capitale de l'Italie ? Reponds en un mot.");
        System.out.println("Reponse L4J : " + reponseL4j.strip());
        verifier(!reponseL4j.isBlank(), "la reponse LangChain4j ne doit pas etre vide");

        OllamaEmbeddingModel embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(Llm.BASE_URL)
                .modelName(Llm.MODELE_EMBEDDING)
                .build();
        int dimL4j = embeddingModel.embed("Bonjour la formation.").content().dimension();
        System.out.println("Dimension embedding L4J : " + dimL4j);
        verifier(dimL4j == 768, "dimension L4J attendue 768, obtenu " + dimL4j);

        System.out.println("\n=== SmokeTest OK : Java pur et LangChain4j fonctionnent ===");
    }

    /** Petite assertion maison : arrete le programme avec un message clair. */
    private static void verifier(boolean condition, String message) {
        if (!condition) {
            System.err.println("ECHEC : " + message);
            System.exit(1);
        }
    }
}
