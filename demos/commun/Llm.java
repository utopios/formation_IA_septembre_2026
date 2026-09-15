package fr.utopios.formation.commun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Couche commune d'acces au LLM local (Ollama) pour toute la formation.
 *
 * <p>Objectif pedagogique : montrer qu'un LLM s'appelle avec une simple
 * requete HTTP + JSON, sans aucun SDK. On utilise uniquement :</p>
 * <ul>
 *   <li>{@link java.net.http.HttpClient} (Java standard depuis Java 11) ;</li>
 *   <li>Jackson pour construire et lire le JSON.</li>
 * </ul>
 *
 * <p>Deux familles de methodes :</p>
 * <ul>
 *   <li>{@link #chat(String)} : generation de texte via POST /api/chat ;</li>
 *   <li>{@link #embed(String)} : vectorisation via POST /api/embeddings.</li>
 * </ul>
 *
 * <p>Le modele de chat par defaut est {@code llama3.2:1b} ; il peut etre
 * remplace sans toucher au code via la variable d'environnement
 * {@code OLLAMA_CHAT_MODEL} (par exemple {@code llama3.2:3b}).</p>
 *
 * <p>Important pour le RAG : le modele d'embedding {@code nomic-embed-text}
 * est ASYMETRIQUE. Un document a indexer et une question de l'utilisateur
 * ne doivent pas etre vectorises avec le meme prefixe : utilisez
 * {@link #embedDocument(String)} pour les documents et
 * {@link #embedQuery(String)} pour les questions.</p>
 */
public class Llm {

    /** URL de base du serveur Ollama local. */
    public static final String BASE_URL = "http://localhost:11434";

    /** Modele de chat par defaut (surchargeable par OLLAMA_CHAT_MODEL). */
    public static final String MODELE_CHAT_DEFAUT = "llama3.2:1b";

    /** Modele d'embedding (dimension 768). */
    public static final String MODELE_EMBEDDING = "nomic-embed-text";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Nombre de tokens du prompt lors du dernier appel a chat (ou -1). */
    private static int derniersTokensPrompt = -1;
    /** Nombre de tokens generes lors du dernier appel a chat (ou -1). */
    private static int derniersTokensReponse = -1;

    private Llm() {
        // Classe utilitaire : pas d'instance.
    }

    /**
     * Retourne le modele de chat effectif : la variable d'environnement
     * OLLAMA_CHAT_MODEL si elle est definie, sinon le modele par defaut.
     */
    public static String modeleChat() {
        String m = System.getenv("OLLAMA_CHAT_MODEL");
        return (m == null || m.isBlank()) ? MODELE_CHAT_DEFAUT : m;
    }

    /**
     * Pose une question au LLM sans message systeme, temperature 0.7.
     *
     * @param prompt la question ou l'instruction de l'utilisateur
     * @return le texte de la reponse du modele
     */
    public static String chat(String prompt) {
        return chat(prompt, null, 0.7);
    }

    /**
     * Pose une question au LLM avec message systeme et temperature.
     *
     * <p>Appelle POST /api/chat en mode non-streaming ({@code stream:false}) :
     * la reponse arrive en un seul bloc JSON, plus simple a exploiter
     * en formation. Les compteurs de tokens (prompt_eval_count et
     * eval_count) sont affiches sur stderr et accessibles ensuite via
     * {@link #derniersTokensPrompt()} et {@link #derniersTokensReponse()}.</p>
     *
     * @param prompt      la question de l'utilisateur
     * @param system      le message systeme (role, consignes), ou null
     * @param temperature creativite du modele (0.0 = deterministe, ~1.0 = creatif)
     * @return le texte de la reponse (champ message.content)
     */
    public static String chat(String prompt, String system, double temperature) {
        ObjectNode corps = MAPPER.createObjectNode();
        corps.put("model", modeleChat());
        corps.put("stream", false);
        corps.putObject("options").put("temperature", temperature);
        var messages = corps.putArray("messages");
        if (system != null && !system.isBlank()) {
            messages.addObject().put("role", "system").put("content", system);
        }
        messages.addObject().put("role", "user").put("content", prompt);

        JsonNode reponse = post("/api/chat", corps);

        derniersTokensPrompt = reponse.path("prompt_eval_count").asInt(-1);
        derniersTokensReponse = reponse.path("eval_count").asInt(-1);
        System.err.printf("[llm] modele=%s tokens_prompt=%d tokens_reponse=%d%n",
                modeleChat(), derniersTokensPrompt, derniersTokensReponse);

        return reponse.path("message").path("content").asText();
    }

    /** Tokens du prompt lors du dernier appel a chat (-1 si aucun appel). */
    public static int derniersTokensPrompt() {
        return derniersTokensPrompt;
    }

    /** Tokens generes lors du dernier appel a chat (-1 si aucun appel). */
    public static int derniersTokensReponse() {
        return derniersTokensReponse;
    }

    /**
     * Vectorise un texte SANS prefixe (usage generique).
     *
     * <p>Pour le RAG, preferez {@link #embedDocument(String)} et
     * {@link #embedQuery(String)} qui appliquent les prefixes attendus
     * par nomic-embed-text.</p>
     *
     * @param texte le texte a vectoriser
     * @return le vecteur d'embedding (dimension 768)
     */
    public static double[] embed(String texte) {
        return embed(texte, "");
    }

    /**
     * Vectorise un texte avec un prefixe de tache.
     *
     * <p>Appelle POST /api/embeddings avec {@code prompt = prefix + texte}.
     * nomic-embed-text est un modele asymetrique : le prefixe indique au
     * modele si le texte est un document a indexer ou une requete.</p>
     *
     * @param texte  le texte a vectoriser
     * @param prefix le prefixe de tache ("search_document: ", "search_query: ", ...)
     * @return le vecteur d'embedding (dimension 768)
     */
    public static double[] embed(String texte, String prefix) {
        ObjectNode corps = MAPPER.createObjectNode();
        corps.put("model", MODELE_EMBEDDING);
        corps.put("prompt", prefix + texte);

        JsonNode reponse = post("/api/embeddings", corps);

        JsonNode vecteur = reponse.path("embedding");
        double[] resultat = new double[vecteur.size()];
        for (int i = 0; i < vecteur.size(); i++) {
            resultat[i] = vecteur.get(i).asDouble();
        }
        return resultat;
    }

    /**
     * Vectorise un DOCUMENT destine a etre indexe (prefixe "search_document: ").
     */
    public static double[] embedDocument(String texte) {
        return embed(texte, "search_document: ");
    }

    /**
     * Vectorise une QUESTION utilisateur (prefixe "search_query: ").
     */
    public static double[] embedQuery(String texte) {
        return embed(texte, "search_query: ");
    }

    /**
     * Similarite cosinus entre deux vecteurs.
     *
     * <p>Valeur entre -1 et 1 : proche de 1 = textes semantiquement proches.
     * C'est LA mesure utilisee par la recherche vectorielle du RAG.</p>
     *
     * @param a premier vecteur
     * @param b second vecteur (meme dimension)
     * @return la similarite cosinus
     */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimensions differentes : " + a.length + " vs " + b.length);
        }
        double produit = 0, normeA = 0, normeB = 0;
        for (int i = 0; i < a.length; i++) {
            produit += a[i] * b[i];
            normeA += a[i] * a[i];
            normeB += b[i] * b[i];
        }
        return produit / (Math.sqrt(normeA) * Math.sqrt(normeB));
    }

    /**
     * Envoie une requete POST JSON a Ollama et retourne le JSON de reponse.
     */
    private static JsonNode post(String chemin, ObjectNode corps) {
        try {
            HttpRequest requete = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + chemin))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(corps)))
                    .build();
            HttpResponse<String> reponse = HTTP.send(requete, HttpResponse.BodyHandlers.ofString());
            if (reponse.statusCode() != 200) {
                throw new IllegalStateException(
                        "Ollama a repondu " + reponse.statusCode() + " sur " + chemin
                                + " : " + reponse.body());
            }
            return MAPPER.readTree(reponse.body());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Impossible de joindre Ollama sur " + BASE_URL
                            + " (le service tourne-t-il ?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Appel a Ollama interrompu", e);
        }
    }
}
