package fr.utopios.formation.module06;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import fr.utopios.formation.commun.Llm;

import java.util.List;


public class RagMaisonL4j {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("MODULE 6 — Le même RAG avec LangChain4j (maison vs framework)");
        System.out.println("=".repeat(70));

        // Les briques du framework, pointees sur le meme Ollama local.
        OllamaEmbeddingModel embeddings = OllamaEmbeddingModel.builder()
                .baseUrl(Llm.BASE_URL)
                .modelName(Llm.MODELE_EMBEDDING)      // nomic-embed-text
                .build();
        OllamaChatModel chat = OllamaChatModel.builder()
                .baseUrl(Llm.BASE_URL)
                .modelName(Llm.modeleChat())          // llama3.2:1b
                .temperature(0.0)
                .build();
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        // INGESTION + INDEXATION : meme corpus que RagMaison.
        // Le prefixe asymetrique reste NOTRE responsabilite, pas celle du framework.
        System.out.println("[index] ingestion de " + RagMaison.DOCUMENTS.size()
                + " documents dans l'InMemoryEmbeddingStore...");
        for (String doc : RagMaison.DOCUMENTS) {
            Embedding e = embeddings.embed("search_document: " + doc).content();
            store.add(e, TextSegment.from(doc));
        }

        // RECUPERATION : top-2 par similarite (le store classe pour nous).
        String question = "Combien de jours de télétravail sont autorisés ?";
        Embedding q = embeddings.embed("search_query: " + question).content();
        List<EmbeddingMatch<TextSegment>> top = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(2)
                .build()).matches();

        System.out.println("\n[récup] top-2 chunks pour : \"" + question + "\"");
        StringBuilder bloc = new StringBuilder();
        for (EmbeddingMatch<TextSegment> m : top) {
            System.out.printf("   (pertinence %.3f) %s%n", m.score(), m.embedded().text());
            bloc.append("- ").append(m.embedded().text()).append('\n');
        }

        // GENERATION : meme prompt ancre sur le contexte que la version maison.
        String prompt = "Tu es un assistant interne. Le CONTEXTE ci-dessous provient de "
                + "documents internes officiels et fiables : tu peux t'y fier. "
                + "Réponds à la question en t'appuyant UNIQUEMENT sur ce contexte, de "
                + "façon directe. Si l'information n'y figure pas, dis-le simplement.\n\n"
                + "CONTEXTE :\n" + bloc + "\nQUESTION : " + question + "\nRÉPONSE :";
        System.out.println("\n=> RÉPONSE : " + chat.chat(prompt).strip());

        System.out.println("\nNote : mêmes 5 étapes que la version maison — le framework");
        System.out.println("fournit les briques (store, modèles), mais les préfixes nomic et");
        System.out.println("le filtrage par permissions restent à VOTRE charge.");
    }
}
