package com.devoxx.watson.controller;

import com.devoxx.watson.exception.ArticleTextExtractionException;
import com.devoxx.watson.exception.DocumentAlreadyExistsException;
import com.devoxx.watson.exception.DocumentThumbnailKeywordsException;
import com.devoxx.watson.exception.SpeechToTextException;
import com.devoxx.watson.model.AlchemyContent;
import com.devoxx.watson.model.ConversationModel;
import com.devoxx.watson.service.*;
import com.devoxx.watson.util.SoupUtil;
import com.ibm.watson.developer_cloud.concept_insights.v2.model.Document;
import com.ibm.watson.developer_cloud.concept_insights.v2.model.Documents;
import com.ibm.watson.developer_cloud.concept_insights.v2.model.Result;
import com.ibm.watson.developer_cloud.dialog.v1.model.Conversation;
import com.ibm.watson.developer_cloud.language_translation.v2.model.Language;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.devoxx.watson.service.ConceptInsightsService.USER_FIELD_THUMBNAIL;
import static com.devoxx.watson.service.ConceptInsightsService.USER_FIELD_THUMBNAIL_KEYWORDS;

/**
 * @author Stephan Janssen
 */
@Component
public class WatsonController {

    private ConceptInsightsService conceptInsightsService;

    private AlchemyLanguageService alchemyLanguageService;

    private SpeechToTextService speechToTextService;

    private ConversationService conversationService;

    private LanguageTranslateService languageTranslateService;

    @Autowired
    public void setConceptInsightsService(final ConceptInsightsService conceptInsightsService) {
        this.conceptInsightsService = conceptInsightsService;
    }

    @Autowired
    public void setAlchemyLanguageService(final AlchemyLanguageService alchemyLanguageService) {
        this.alchemyLanguageService = alchemyLanguageService;
    }

    @Autowired
    public void setSpeechToTextService(final SpeechToTextService speechToTextService) {
        this.speechToTextService = speechToTextService;
    }

    @Autowired
    public void setConversationService(final ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Autowired
    public void setLanguageTranslateService(final LanguageTranslateService languageTranslateService) {
        this.languageTranslateService = languageTranslateService;
    }

    /**
     * Process an article or video link.
     *
     * @param link the article or presentation link
     * @throws DocumentAlreadyExistsException  the document already exists
     * @throws DocumentThumbnailKeywordsException document has no thumbnail exception
     * @return the alchemy content
     */
    AlchemyContent processLink(final String link) throws DocumentAlreadyExistsException,
                                                         DocumentThumbnailKeywordsException,
                                                         ArticleTextExtractionException {

        final AlchemyContent content = new AlchemyContent(link);

        final Document document = conceptInsightsService.findDocument(content.getId());

        if (document == null) {

            content.setThumbnail(SoupUtil.getThumbnail(link));

            content.setContent(alchemyLanguageService.getArticleText(link));

            alchemyLanguageService.process(content);

            conceptInsightsService.createDocument(content);

            return content;
        }

        // Does document have thumbnail keywords?
        // This piece of code was introduced for existing Watson documents which had no thumbnail processing
        final Map<String, String> userFields = document.getUserFields();

        if (!userFields.containsKey(USER_FIELD_THUMBNAIL_KEYWORDS)) {
            try {
                final String thumbnailKeywords = alchemyLanguageService.getThumbnailKeywords(userFields.get(USER_FIELD_THUMBNAIL));

                userFields.put(USER_FIELD_THUMBNAIL_KEYWORDS, thumbnailKeywords);

                conceptInsightsService.updateDocument(document);

            } catch (IOException e) {
                throw new DocumentThumbnailKeywordsException();
            }
        } else {
            throw new DocumentAlreadyExistsException();
        }

        return content;
    }

    /**
     * Search the Corpus documents.
     *
     * @param searchValue   the search value
     * @return list of document results
     */
    List<Result> searchDocuments(final String searchValue) {
        if (searchValue.isEmpty()) {
            return null;
        } else {
            return conceptInsightsService.searchDocuments(searchValue);
        }
    }

    /**
     * Return all Corpus documents
     *
     * @param limit limit the result set
     * @return list of Corpus documents
     */
    Documents getAllDocuments(final int limit) {
        return conceptInsightsService.getAllDocuments(limit);
    }

    /**
     * Find a document based on unique id.
     *
     * @param documentId    the document id
     * @return the relate Corpus document
     */
    Document findDocument(final String documentId) {
        if (documentId.isEmpty()) {
            return null;
        } else {
            return conceptInsightsService.findDocument(documentId);
        }
    }

    /**
     * Process speech to text.
     *
     * @param audioFile the audio file
     * @param docName   the doc name
     * @param audioAbstract the abstract
     * @return returns the transcript
     */
    public String processSpeechToText(final File audioFile,
                                      final String docName,
                                      final String audioAbstract,
                                      final String audioModel) throws SpeechToTextException {

        String transcript = speechToTextService.processAudioFile(audioFile, docName, audioAbstract, audioModel);

        if (audioModel.contains("FR")) {
            transcript = languageTranslateService.translate(transcript, Language.FRENCH, Language.ENGLISH);
        }

        return transcript;
    }

    /**
     * Create an audio based Corpus document.
     *
     * @param docName the document name
     * @param link    the youtube link
     * @param transcript the audio transcript
     */
    public void createAudioDocument(final String docName,
                                    final String link,
                                    final String transcript,
                                    final String speakers) {

        final AlchemyContent content = new AlchemyContent(link);
        content.setAuthors(speakers);
        content.setTitle(docName);
        content.setContent(transcript);

        alchemyLanguageService.process(content);

        conceptInsightsService.createDocument(content);
    }

    /**
     * Initialize the conversation.
     *
     * @param dialogId the unique dialog ID
     * @return the dialog conversation
     */
    ConversationModel initConversation(final boolean firstTimeUser,
                                       final String dialogId) {

        final Conversation conversation = conversationService.initDialog(firstTimeUser, dialogId);

        final ConversationModel conversationModel = new ConversationModel();
        conversationModel.setClientId(Integer.toString(conversation.getClientId()));
        conversationModel.setConversationId(Integer.toString(conversation.getId()));
        conversationModel.setInput(conversation.getInput());
        conversationModel.setWatsonResponse(StringUtils.join(conversation.getResponse(), " "));
        return conversationModel;
    }
}
