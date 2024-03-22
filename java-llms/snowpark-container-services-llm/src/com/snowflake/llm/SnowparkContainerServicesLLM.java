package com.snowflake.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.dataiku.common.rpc.ExternalJSONAPIClient;
import com.dataiku.dip.custom.PluginSettingsResolver.ResolvedSettings;
import com.dataiku.dip.llm.custom.CustomLLMClient;
import com.dataiku.dip.llm.local.HuggingFaceLocalClient;
import com.dataiku.dip.connections.HuggingFaceLocalConnection;
import com.dataiku.dip.llm.online.LLMClient.ChatMessage;
import com.dataiku.dip.llm.online.LLMClient.CompletionQuery;
import com.dataiku.dip.llm.online.LLMClient.EmbeddingQuery;
import com.dataiku.dip.llm.online.LLMClient.SimpleCompletionResponse;
import com.dataiku.dip.llm.online.LLMClient.SimpleEmbeddingResponse;
import com.dataiku.dip.resourceusage.ComputeResourceUsage;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.InternalLLMUsageData;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageData;
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageType;
import com.dataiku.dss.shadelib.org.apache.http.impl.client.LaxRedirectStrategy;
import com.dataiku.dip.connections.AbstractLLMConnection.HTTPBasedLLMNetworkSettings;
import com.dataiku.dip.llm.utils.OnlineLLMUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import com.dataiku.dss.shadelib.org.apache.http.impl.client.HttpClientBuilder;

import com.dataiku.dip.shaker.processors.expr.TokenizedText;
import com.dataiku.dip.utils.DKULogger;
import com.dataiku.dip.utils.JSON;
import com.dataiku.dip.utils.JF;
import com.dataiku.dip.utils.JF.ObjectBuilder;

// When the Signature of com.dataiku.dip.llm.custom.CustomLLMClient changes, this will need to change to com.dataiku.dip.llm.LLMStructuredRef
import com.dataiku.dip.llm.promptstudio.PromptStudio.LLMStructuredRef;

public class SnowparkContainerServicesLLM extends CustomLLMClient {
    public SnowparkContainerServicesLLM(){}
    private String llmEndpointUrl;
    private String snowflakeAccountURL;
    private String modelHandlingMode = "TEXT_GENERATION_OTHER";
    private int maxParallelism = 1;
    private double spcsCreditsPerHourCost = 1.0;
    private double snowflakeCreditCost = 3.0;

    ResolvedSettings rs;
    
    
    private ExternalJSONAPIClient llmClient;

    private InternalLLMUsageData usageData = new LLMUsageData();
    HTTPBasedLLMNetworkSettings networkSettings = new HTTPBasedLLMNetworkSettings();
    Gson gson = new Gson();

    public void init(ResolvedSettings settings) {
        this.rs = settings;
        llmEndpointUrl = rs.config.get("llmEndpointUrl").getAsString();
        snowflakeAccountURL = rs.config.get("snowflakeAccountUrl").getAsString();
        modelHandlingMode = rs.config.get("modelHandlingMode").getAsString();
        maxParallelism = rs.config.get("maxParallelism").getAsInt();
        spcsCreditsPerHourCost = rs.config.get("spcsCreditsPerHourCost").getAsDouble();
        snowflakeCreditCost = rs.config.get("snowflakeCreditCost").getAsDouble();
        String access_token = rs.config.get("oauth").getAsJsonObject().get("snowflake_oauth").getAsString();

        Consumer<HttpClientBuilder> customizeBuilderCallback = (builder) -> {  
            builder.setRedirectStrategy(new LaxRedirectStrategy());  
            HTTPBasedLLMNetworkSettings networkSettings = new HTTPBasedLLMNetworkSettings();  
            OnlineLLMUtils.add429RetryStrategy(builder, networkSettings);  
        };  
        // Begin Oauth to Session Token Dance 
        ExternalJSONAPIClient tokenClient = new ExternalJSONAPIClient(snowflakeAccountURL, null, true, com.dataiku.dip.ApplicationConfigurator.getProxySettings(), customizeBuilderCallback);
        JsonObject tokenRequestBody= new JsonObject();
        tokenRequestBody.addProperty("AUTHENTICATOR", "OAUTH");
        tokenRequestBody.addProperty("TOKEN", access_token);
        JsonObject trData = new JsonObject();
        trData.add("data",tokenRequestBody);

        JsonObject tokenResp=new JsonObject();
        try {
            tokenResp = tokenClient.postObjectToJSON("/session/v1/login-request", JsonObject.class, trData);
        } catch (IOException e) {
            logger.error("SPCS session token exchange failed",e);
        }
        String sessionStr=tokenResp.get("data").getAsJsonObject().get("token").getAsString();
        String snowflakeToken =  "Snowflake Token=\""+sessionStr+"\"";
        // Decorate header with session token
        llmClient = new ExternalJSONAPIClient(llmEndpointUrl, null, true, com.dataiku.dip.ApplicationConfigurator.getProxySettings(), customizeBuilderCallback);  
        llmClient.addHeader("Authorization", snowflakeToken);
    }

    public int getMaxParallelism() {
        return maxParallelism;
    }

    @Override
    public synchronized List<SimpleCompletionResponse> completeBatch(List<CompletionQuery> completionQueries) throws IOException {
        List<SimpleCompletionResponse> ret = new ArrayList<>();

        for (CompletionQuery query: completionQueries) {
            long before = System.currentTimeMillis();
            SimpleCompletionResponse scr = null;

            logger.info("SPCS LLM Complete: " + JSON.json(query));
            scr = chatComplete(query.messages, query.settings.maxOutputTokens, query.settings.temperature,
                    query.settings.topP, query.settings.topK, query.settings.stopSequences);
            
            usageData.totalComputationTimeMS += (System.currentTimeMillis() - before);
            double estimatedCostUSD = spcsCreditsPerHourCost*snowflakeCreditCost*usageData.totalComputationTimeMS/3600000;
            usageData.estimatedCostUSD += estimatedCostUSD;
            scr.estimatedCost = estimatedCostUSD;

            usageData.totalPromptTokens += scr.promptTokens;
            usageData.totalCompletionTokens += scr.completionTokens;

            ret.add(scr);
        }
        return ret;
    }

    public SimpleCompletionResponse chatComplete(List<ChatMessage> messages, Integer maxTokens,
            Double temperature, Double topP, Integer topK, List<String> stopSequences) throws IOException {
        
        String completePrompt = "";
        if (modelHandlingMode.equals("TEXT_GENERATION_LLAMA_2")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_LLAMA_2);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_MISTRAL")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_MISTRAL);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_ZEPHYR")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_ZEPHYR);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_FALCON")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_FALCON);
        } else {
            completePrompt += messages.stream().map(msg -> msg.getText()).collect(Collectors.joining("\n\n"));
        }
        logger.info("Model Handling Mode: " + modelHandlingMode);
        logger.info("Complete Prompt: " + completePrompt);

        ObjectBuilder ob = JF.obj();

        JsonArray jsonMessages = new JsonArray();
        JsonArray jsonMessage = new JsonArray();
        jsonMessage.add(Integer.valueOf(0));
        jsonMessage.add(JF.obj().withJSON("inputs", completePrompt).get());
        jsonMessages.add(jsonMessage);

        ob.withJSON("data", jsonMessages);

        logger.info("Raw SPCS LLM chat completion: " + JSON.pretty(ob.get()));
        String endpoint = llmEndpointUrl + "/predict";
        logger.info("posting to SPCS LLM at: "+ endpoint);
        JsonObject response = llmClient.postObjectToJSON("/predict", networkSettings.queryTimeoutMS, JsonObject.class, ob.get());
        JsonArray generations = response.get("data").getAsJsonArray();
        if (generations.size() != 1) {
            throw new IllegalArgumentException("Did not get a single generation??");
        }
        JsonArray generation0 = generations.get(0).getAsJsonArray();
        JsonObject generation1 = generation0.get(1).getAsJsonObject();
        
        //SPCS returns an array of strings which contain JSON, extract String and parse as JSON
        String text = generation1.get("outputs").getAsString();
        JsonArray gen3 = gson.fromJson(text, JsonArray.class);
        String genText = gen3.get(0).getAsJsonObject().get("generated_text").getAsString();

        // And build the final result
        SimpleCompletionResponse completionResponse = new SimpleCompletionResponse();
        completionResponse.text = genText;

        completionResponse.promptTokens =  (int)(2.5f * new TokenizedText(completePrompt).size());
        completionResponse.completionTokens = (int)(2.5f *  new TokenizedText(genText).size());
        completionResponse.tokenCountsAreEstimated = true;

        return completionResponse;
    }

    public SimpleEmbeddingResponse embed(String text) throws IOException {
        
        ObjectBuilder ob = JF.obj();

        JsonArray jsonMessages = new JsonArray();
        JsonArray jsonMessage = new JsonArray();
        jsonMessage.add(Integer.valueOf(0));
        JsonObject jo = JF.obj().withJSON("inputs", text).get();
        jsonMessage.add(jo);
        jsonMessages.add(jsonMessage);

        ob.withJSON("data", jsonMessages);

        logger.info("Raw SPCS LLM embed: " + JSON.pretty(ob.get()));

        String endpoint = llmEndpointUrl + "/predict";
        logger.info("posting to SPCS LLM at: "+ endpoint);

        JsonObject response = llmClient.postObjectToJSON("/predict", networkSettings.queryTimeoutMS, JsonObject.class, ob.get());
            
        JsonArray generations = response.get("data").getAsJsonArray();
        
        if (generations.size() != 1) {
            throw new IllegalArgumentException("Did not get a single generation??");
        }
        
        JsonArray generation0 = generations.get(0).getAsJsonArray();
        JsonObject generation1 = generation0.get(1).getAsJsonObject();
        String embedArrayStr = generation1.get("outputs").getAsString();
        JsonArray embedArray = JSON.parse(embedArrayStr,JsonArray.class);
        double[] embeds = new double[embedArray.size()];
        Iterator<JsonElement> arrayIT = embedArray.iterator();
        int i=0;
        while (arrayIT.hasNext())
        {
            embeds[i]=arrayIT.next().getAsDouble();
            i++;
        }
        SimpleEmbeddingResponse ret = new SimpleEmbeddingResponse();
        ret.embedding = embeds;
        ret.promptTokens =  (int)(2.5f * new TokenizedText(text).size());
        ret.tokenCountsAreEstimated = true;

        return ret;
    }

    public List<SimpleEmbeddingResponse> embedBatch(List<EmbeddingQuery> queries) throws IOException {
        List<SimpleEmbeddingResponse> ret = new ArrayList<>();

        for (EmbeddingQuery query: queries) {
            long before = System.currentTimeMillis();
            SimpleEmbeddingResponse scr = null;

            logger.info("SPCS LLM Complete: " + JSON.json(query));
            scr = embed(query.text);
            
            usageData.totalComputationTimeMS += (System.currentTimeMillis() - before);
            double estimatedCostUSD = spcsCreditsPerHourCost*snowflakeCreditCost*usageData.totalComputationTimeMS/3600000;
            usageData.estimatedCostUSD += estimatedCostUSD;
            scr.estimatedCost = estimatedCostUSD;
            usageData.totalPromptTokens += scr.promptTokens;

            ret.add(scr);
        }
        return ret;
    }

    //@Override
    public ComputeResourceUsage getTotalCRU(LLMUsageType usageType, LLMStructuredRef llmRef) {
        
        ComputeResourceUsage cru = new ComputeResourceUsage();
        cru.setupLLMUsage(usageType, llmRef.connection, llmRef.type.toString());
        cru.llmUsage.setFromInternal(this.usageData);
        return cru; 
    }

    private static DKULogger logger = DKULogger.getLogger("dku.llm.spcsplugin");

 
}
