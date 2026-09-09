package fr.utopios.formation.module05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.utopios.formation.commun.Llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


public class FunctionCallingEtStreaming {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Premier objet JSON trouve dans un texte (l'appel d'outil du modele). */
    private static final Pattern OBJET_JSON = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    // ----------------------------------------------------------------------
    // Les OUTILS Java reels (ce que le modele peut "appeler")
    // ----------------------------------------------------------------------

    /** Effectue une operation arithmetique simple — du code deterministe. */
    static double calculatrice(String operation, double a, double b) {
        return switch (operation) {
            case "addition" -> a + b;
            case "soustraction" -> a - b;
            case "multiplication" -> a * b;
            case "division" -> (b != 0) ? a / b : Double.NaN;
            default -> throw new IllegalArgumentException("operation inconnue : " + operation);
        };
    }

    /** Meteo MOCKEE (pas d'appel reseau) — pour la demo d'orchestration. */
    static Map<String, Object> meteo(String ville) {
        return switch (ville.toLowerCase()) {
            case "paris" -> Map.of("temp_c", 18, "ciel", "nuageux");
            case "lyon" -> Map.of("temp_c", 22, "ciel", "ensoleillé");
            case "lille" -> Map.of("temp_c", 15, "ciel", "pluvieux");
            default -> Map.of("temp_c", 20, "ciel", "inconnu");
        };
    }

    // ----------------------------------------------------------------------
    // Le PROTOCOLE JSON explicite : la "declaration d'outils" du pauvre,
    // qui est en fait la meme chose que les schemas tool use des API cloud.
    // ----------------------------------------------------------------------

    private static final String SYSTEME = """
            Tu es un assistant qui peut utiliser des outils.

            Outils disponibles :
            1. calculatrice — calcule une operation arithmetique entre deux nombres.
               Appel : {"outil": "calculatrice", "arguments": {"operation": "addition|soustraction|multiplication|division", "a": nombre, "b": nombre}}
            2. meteo — donne la meteo actuelle d'une ville.
               Appel : {"outil": "meteo", "arguments": {"ville": "nom de la ville"}}

            Regles STRICTES :
            - Si la question demande un calcul ou la meteo, reponds UNIQUEMENT avec
              l'objet JSON de l'appel d'outil, sans aucun autre texte.
            - Quand un message "RESULTAT DE L'OUTIL" arrive, n'appelle PLUS d'outil :
              donne la reponse finale en francais, en citant le resultat tel quel.
            """;

    /**
     * Extrait un appel d'outil du texte du modele, ou null si c'est une
     * reponse finale. Accepte "outil" (notre protocole) et "name"/"arguments"
     * (variantes qu'un petit modele emet parfois spontanement), et repare
     * l'accolade ouvrante qu'un 1b oublie de temps en temps.
     */
    static ObjectNode extraireAppelOutil(String texte) {
        JsonNode obj = premierObjetJson(texte);
        // Repli robustesse : le 1b emet parfois  "outil": ..., {...}}  sans
        // l'accolade ouvrante. On la restaure et on retente.
        if (nomOutil(obj) == null && texte.contains("\"outil\"")) {
            obj = premierObjetJson("{" + texte.substring(texte.indexOf("\"outil\"")));
        }
        String nom = nomOutil(obj);
        if (nom == null) {
            return null;                       // pas d'appel d'outil -> texte final
        }
        JsonNode args = obj.has("arguments") ? obj.get("arguments") : obj.get("parameters");
        ObjectNode appel = MAPPER.createObjectNode();
        appel.put("outil", nom);
        appel.set("arguments", args == null ? MAPPER.createObjectNode() : args);
        return appel;
    }

    /** Premier objet JSON parsable du texte (tolere une accolade en trop). */
    private static JsonNode premierObjetJson(String texte) {
        Matcher m = OBJET_JSON.matcher(texte);
        if (!m.find()) {
            return null;
        }
        String candidat = m.group();
        for (int essai = 0; essai < 3; essai++) {
            try {
                return MAPPER.readTree(candidat);
            } catch (Exception e) {
                if (!candidat.endsWith("}")) {
                    return null;
                }
                candidat = candidat.substring(0, candidat.length() - 1).strip();
            }
        }
        return null;
    }

    /** Nom d'outil connu porte par l'objet, ou null. */
    private static String nomOutil(JsonNode obj) {
        if (obj == null) {
            return null;
        }
        String nom = obj.path("outil").asText(obj.path("name").asText(null));
        return ("calculatrice".equals(nom) || "meteo".equals(nom)) ? nom : null;
    }

    /** Execute l'outil demande et renvoie le resultat serialise en JSON. */
    static String executerOutil(ObjectNode appel) {
        String nom = appel.get("outil").asText();
        JsonNode args = appel.get("arguments");
        try {
            if ("calculatrice".equals(nom)) {
                // Coercition de types : un petit modele envoie parfois "23" (string).
                double resultat = calculatrice(
                        args.path("operation").asText(),
                        args.path("a").asDouble(),
                        args.path("b").asDouble());
                return String.valueOf(resultat);
            }
            // meteo : la ville est parfois nichee — on prend le champ "ville".
            Map<String, Object> m = meteo(args.path("ville").asText());
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"erreur\": \"" + e.getMessage() + "\"}";  // jamais d'exception brute
        }
    }

    // ----------------------------------------------------------------------
    // A) La boucle d'orchestration : decider -> executer -> renvoyer -> boucler
    // ----------------------------------------------------------------------

    /** Un tour de chat multi-messages contre POST /api/chat (stream:false). */
    private static String tourDeChat(ArrayNode messages) throws Exception {
        ObjectNode corps = MAPPER.createObjectNode();
        corps.put("model", Llm.modeleChat());
        corps.put("stream", false);
        corps.putObject("options").put("temperature", 0.0);   // reproductibilite
        corps.set("messages", messages);

        HttpRequest requete = HttpRequest.newBuilder()
                .uri(URI.create(Llm.BASE_URL + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(corps)))
                .build();
        HttpResponse<String> reponse = HTTP.send(requete, HttpResponse.BodyHandlers.ofString());
        if (reponse.statusCode() != 200) {
            throw new IllegalStateException("Ollama a repondu " + reponse.statusCode()
                    + " : " + reponse.body());
        }
        return MAPPER.readTree(reponse.body()).path("message").path("content").asText();
    }

    /** Ajoute un message {role, content} a l'historique. */
    private static void empiler(ArrayNode messages, String role, String contenu) {
        ObjectNode msg = messages.addObject();
        msg.put("role", role);
        msg.put("content", contenu);
    }

    /**
     * L'assistant a outils : le modele DECIDE, l'application EXECUTE,
     * on RENVOIE le resultat, et on BOUCLE jusqu'a la reponse finale.
     */
    static String assistant(String question, int maxTours) throws Exception {
        System.out.println("\n[Question] " + question);
        ArrayNode messages = MAPPER.createArrayNode();
        empiler(messages, "system", SYSTEME);
        empiler(messages, "user", question);

        for (int tour = 1; tour <= maxTours; tour++) {
            String contenu = tourDeChat(messages);
            ObjectNode appel = extraireAppelOutil(contenu);

            if (appel == null) {
                // Pas d'appel d'outil -> reponse finale.
                System.out.println("  (tour " + tour + ") réponse finale du modèle.");
                return contenu.strip();
            }

            System.out.println("  (tour " + tour + ") le modèle appelle : "
                    + appel.get("outil").asText() + "(" + appel.get("arguments") + ")");
            String resultat = executerOutil(appel);
            System.out.println("            -> résultat exécuté : " + resultat);

            // On re-empile la decision du modele AVANT le resultat d'outil,
            // puis on renvoie le resultat : meme discipline que tool_use/tool_result.
            empiler(messages, "assistant", contenu);
            empiler(messages, "user", "RESULTAT DE L'OUTIL " + appel.get("outil").asText()
                    + " : " + resultat
                    + "\nDonne maintenant la réponse finale en français en citant précisément les valeurs de ce résultat.");
        }
        return "(limite de tours atteinte sans réponse finale)";
    }

    // ----------------------------------------------------------------------
    // B) Streaming : POST /api/chat stream:true, lecture ligne par ligne
    // ----------------------------------------------------------------------

    static void demoStreaming() throws Exception {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("B) Streaming (Ollama, token par token)");
        System.out.println("=".repeat(70));
        System.out.print("Réponse en flux : ");
        System.out.flush();

        ObjectNode corps = MAPPER.createObjectNode();
        corps.put("model", Llm.modeleChat());
        corps.put("stream", true);                       // <- LE parametre du streaming
        corps.putArray("messages").addObject()
                .put("role", "user")
                .put("content", "Énumère 3 avantages du streaming, très brièvement.");

        HttpRequest requete = HttpRequest.newBuilder()
                .uri(URI.create(Llm.BASE_URL + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(corps)))
                .build();

        // En streaming, Ollama envoie UN OBJET JSON PAR LIGNE (NDJSON).
        // BodyHandlers.ofLines() nous donne ces lignes au fil de l'eau.
        int morceaux = 0;
        HttpResponse<Stream<String>> reponse =
                HTTP.send(requete, HttpResponse.BodyHandlers.ofLines());
        for (String ligne : (Iterable<String>) reponse.body()::iterator) {
            if (ligne.isBlank()) {
                continue;
            }
            JsonNode chunk = MAPPER.readTree(ligne);
            System.out.print(chunk.path("message").path("content").asText());
            System.out.flush();                          // affichage immediat
            morceaux++;
        }
        System.out.println("\n[streaming terminé : " + morceaux + " morceaux reçus]");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(70));
        System.out.println("MODULE 5 — Function calling / tool use + orchestration + streaming");
        System.out.println("=".repeat(70));

        System.out.println("\n### A) Boucle d'orchestration LOCALE (Ollama, protocole JSON) ###");
        String[] questions = {
                "Combien font 23 multiplié par 19 ?",
                "Utilise l'outil meteo pour me donner la météo de la ville de Lyon.",
        };
        for (String q : questions) {
            String rep = assistant(q, 5);
            System.out.println("  REPONSE : " + rep);
        }

        demoStreaming();
    }
}
