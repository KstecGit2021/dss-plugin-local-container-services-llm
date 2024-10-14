package com.snowflake.llm;  // 패키지 선언: Snowflake LLM 관련 클래스가 포함된 패키지

import java.io.IOException; // IOException을 사용하기 위한 import
import java.util.ArrayList; // ArrayList를 사용하기 위한 import
import java.util.Iterator; // Iterator를 사용하기 위한 import
import java.util.List; // List 인터페이스를 사용하기 위한 import

import java.util.function.Consumer; // Consumer 인터페이스를 사용하기 위한 import
import java.util.stream.Collectors; // Stream API의 Collectors를 사용하기 위한 import

// Dataiku 관련 패키지 import
import com.dataiku.common.rpc.ExternalJSONAPIClient; // 외부 JSON API 클라이언트
import com.dataiku.dip.custom.PluginSettingsResolver.ResolvedSettings; // 플러그인 설정 해석기
import com.dataiku.dip.llm.custom.CustomLLMClient; // 커스텀 LLM 클라이언트
import com.dataiku.dip.llm.local.HuggingFaceLocalClient; // Hugging Face 로컬 클라이언트
import com.dataiku.dip.connections.HuggingFaceLocalConnection; // Hugging Face 로컬 연결
import com.dataiku.dip.llm.online.LLMClient.ChatMessage; // LLM 클라이언트의 채팅 메시지
import com.dataiku.dip.llm.online.LLMClient.CompletionQuery; // LLM 클라이언트의 완료 쿼리
import com.dataiku.dip.llm.online.LLMClient.EmbeddingQuery; // LLM 클라이언트의 임베딩 쿼리
import com.dataiku.dip.llm.online.LLMClient.SimpleCompletionResponse; // 단순 완료 응답
import com.dataiku.dip.llm.online.LLMClient.SimpleEmbeddingResponse; // 단순 임베딩 응답
import com.dataiku.dip.resourceusage.ComputeResourceUsage; // 컴퓨팅 자원 사용
import com.dataiku.dip.resourceusage.ComputeResourceUsage.InternalLLMUsageData; // 내부 LLM 사용 데이터
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageData; // LLM 사용 데이터
import com.dataiku.dip.resourceusage.ComputeResourceUsage.LLMUsageType; // LLM 사용 유형
import com.dataiku.dss.shadelib.org.apache.http.impl.client.LaxRedirectStrategy; // 느슨한 리디렉션 전략
import com.dataiku.dip.connections.AbstractLLMConnection.HTTPBasedLLMNetworkSettings; // HTTP 기반 LLM 네트워크 설정
import com.dataiku.dip.llm.utils.OnlineLLMUtils; // 온라인 LLM 유틸리티
import com.google.gson.Gson; // Gson 라이브러리
import com.google.gson.JsonArray; // JSON 배열
import com.google.gson.JsonObject; // JSON 객체
import com.google.gson.JsonElement; // JSON 요소

import com.dataiku.dss.shadelib.org.apache.http.impl.client.HttpClientBuilder; // HTTP 클라이언트 빌더

import com.dataiku.dip.shaker.processors.expr.TokenizedText; // 토큰화된 텍스트
import com.dataiku.dip.utils.DKULogger; // Dataiku 로거
import com.dataiku.dip.utils.JSON; // JSON 유틸리티
import com.dataiku.dip.utils.JF; // JF 유틸리티
import com.dataiku.dip.utils.JF.ObjectBuilder; // JF 객체 빌더

// CustomLLMClient의 서명이 변경되면 아래의 import도 변경해야 함
import com.dataiku.dip.llm.promptstudio.PromptStudio.LLMStructuredRef; // LLM 구조적 참조

public class SnowparkContainerServicesLLM extends CustomLLMClient { // SnowparkContainerServicesLLM 클래스 정의
    public SnowparkContainerServicesLLM(){} // 기본 생성자

    private String llmEndpointUrl; // LLM 엔드포인트 URL
    private String snowflakeAccountURL; // Snowflake 계정 URL
    private String modelHandlingMode = "TEXT_GENERATION_OTHER"; // 모델 처리 모드 (기본값)
    private int maxParallelism = 1; // 최대 병렬 처리 수 (기본값)
    private double spcsCreditsPerHourCost = 1.0; // SPCS 시간당 크레딧 비용 (기본값)
    private double snowflakeCreditCost = 3.0; // Snowflake 크레딧 비용 (기본값)

    ResolvedSettings rs; // 해석된 설정 저장

    private ExternalJSONAPIClient llmClient; // LLM 클라이언트

    private InternalLLMUsageData usageData = new LLMUsageData(); // LLM 사용 데이터
    HTTPBasedLLMNetworkSettings networkSettings = new HTTPBasedLLMNetworkSettings(); // HTTP 기반 네트워크 설정
    Gson gson = new Gson(); // Gson 객체 생성

    public void init(ResolvedSettings settings) { // 초기화 메서드
        this.rs = settings; // 설정 저장
        llmEndpointUrl = rs.config.get("llmEndpointUrl").getAsString(); // LLM 엔드포인트 URL 가져오기
        snowflakeAccountURL = rs.config.get("snowflakeAccountUrl").getAsString(); // Snowflake 계정 URL 가져오기
        modelHandlingMode = rs.config.get("modelHandlingMode").getAsString(); // 모델 처리 모드 가져오기
        maxParallelism = rs.config.get("maxParallelism").getAsInt(); // 최대 병렬 처리 수 가져오기
        spcsCreditsPerHourCost = rs.config.get("spcsCreditsPerHourCost").getAsDouble(); // SPCS 크레딧 비용 가져오기
        snowflakeCreditCost = rs.config.get("snowflakeCreditCost").getAsDouble(); // Snowflake 크레딧 비용 가져오기
        String access_token = rs.config.get("oauth").getAsJsonObject().get("snowflake_oauth").getAsString(); // OAuth 토큰 가져오기

        Consumer<HttpClientBuilder> customizeBuilderCallback = (builder) -> { // HTTP 클라이언트 설정을 커스터마이즈하는 콜백
            builder.setRedirectStrategy(new LaxRedirectStrategy()); // 느슨한 리디렉션 전략 설정
            HTTPBasedLLMNetworkSettings networkSettings = new HTTPBasedLLMNetworkSettings(); // 네트워크 설정 생성
            OnlineLLMUtils.add429RetryStrategy(builder, networkSettings); // 429 오류에 대한 재시도 전략 추가
        };

        // OAuth를 통한 세션 토큰 요청 시작
        ExternalJSONAPIClient tokenClient = new ExternalJSONAPIClient(snowflakeAccountURL, null, true, com.dataiku.dip.ApplicationConfigurator.getProxySettings(), customizeBuilderCallback); // 외부 JSON API 클라이언트 생성
        JsonObject tokenRequestBody = new JsonObject(); // 토큰 요청 본문 JSON 객체 생성
        tokenRequestBody.addProperty("AUTHENTICATOR", "OAUTH"); // 인증 방식 설정
        tokenRequestBody.addProperty("TOKEN", access_token); // 액세스 토큰 추가
        JsonObject trData = new JsonObject(); // 요청 데이터 JSON 객체 생성
        trData.add("data", tokenRequestBody); // 요청 데이터에 본문 추가

        JsonObject tokenResp = new JsonObject(); // 토큰 응답 JSON 객체 생성
        try {
            tokenResp = tokenClient.postObjectToJSON("/session/v1/login-request", JsonObject.class, trData); // 세션 로그인 요청
        } catch (IOException e) {
            logger.error("SPCS session token exchange failed", e); // 오류 발생 시 로깅
        }
        String sessionStr = tokenResp.get("data").getAsJsonObject().get("token").getAsString(); // 세션 토큰 가져오기
        String snowflakeToken = "Snowflake Token=\"" + sessionStr + "\""; // Snowflake 토큰 형식화
        // 헤더에 세션 토큰 추가
        llmClient = new ExternalJSONAPIClient(llmEndpointUrl, null, true, com.dataiku.dip.ApplicationConfigurator.getProxySettings(), customizeBuilderCallback); // LLM 클라이언트 생성
        llmClient.addHeader("Authorization", snowflakeToken); // 인증 헤더 추가
    }

    public int getMaxParallelism() { // 최대 병렬 처리 수 반환
        return maxParallelism; // 최대 병렬 처리 수 반환
    }

    @Override // 오버라이드된 메서드
    public synchronized List<SimpleCompletionResponse> completeBatch(List<CompletionQuery> completionQueries) throws IOException { // 배치 완료 메서드
        List<SimpleCompletionResponse> ret = new ArrayList<>(); // 응답 리스트 생성

        for (CompletionQuery query : completionQueries) { // 각 완료 쿼리에 대해 반복
            long before = System.currentTimeMillis(); // 시작 시간 기록
            SimpleCompletionResponse scr = null; // 단순 완료 응답 초기화

            logger.info("SPCS LLM Complete: " + JSON.json(query)); // 로깅: 완료 쿼리
            scr = chatComplete(query.messages, query.settings.maxOutputTokens, query.settings.temperature, // 채팅 완료 메서드 호출
                    query.settings.topP, query.settings.topK, query.settings.stopSequences);

            usageData.totalComputationTimeMS += (System.currentTimeMillis() - before); // 총 계산 시간 갱신
            double estimatedCostUSD = spcsCreditsPerHourCost * snowflakeCreditCost * usageData.totalComputationTimeMS / 3600000; // 예상 비용 계산
            usageData.estimatedCostUSD += estimatedCostUSD; // 총 비용 갱신
            scr.estimatedCost = estimatedCostUSD; // 완료 응답에 예상 비용 추가

            usageData.totalPromptTokens += scr.promptTokens; // 총 프롬프트 토큰 갱신
            usageData.totalCompletionTokens += scr.completionTokens; // 총 완료 토큰 갱신

            ret.add(scr); // 응답 리스트에 추가
        }
        return ret; // 응답 리스트 반환
    }

    public SimpleCompletionResponse chatComplete(List<ChatMessage> messages, Integer maxTokens, // 채팅 완료 메서드
            Double temperature, Double topP, Integer topK, List<String> stopSequences) throws IOException {
        String completePrompt = ""; // 완전 프롬프트 초기화
        // 모델 처리 모드에 따라 프롬프트 구성
        if (modelHandlingMode.equals("TEXT_GENERATION_LLAMA_2")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_LLAMA_2);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_MISTRAL")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_MISTRAL);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_ZEPHYR")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_ZEPHYR);
        } else if (modelHandlingMode.equals("TEXT_GENERATION_FALCON")) {
            completePrompt += HuggingFaceLocalClient.getFormattedPromptContent(messages, HuggingFaceLocalConnection.HuggingFaceHandlingMode.TEXT_GENERATION_FALCON);
        } else {
            completePrompt += messages.stream().map(msg -> msg.getText()).collect(Collectors.joining("\n\n")); // 기본 프롬프트 구성
        }
        logger.info("Model Handling Mode: " + modelHandlingMode); // 로깅: 모델 처리 모드
        logger.info("Complete Prompt: " + completePrompt); // 로깅: 완전 프롬프트

        ObjectBuilder ob = JF.obj(); // JSON 객체 빌더 생성
        JsonArray jsonMessages = new JsonArray(); // 메시지 JSON 배열 생성
        JsonArray jsonMessage = new JsonArray(); // 개별 메시지 JSON 배열 생성
        jsonMessage.add(Integer.valueOf(0)); // 인덱스 추가
        jsonMessage.add(JF.obj().withJSON("inputs", completePrompt).get()); // 프롬프트 추가
        jsonMessages.add(jsonMessage); // 메시지 배열에 추가

        ob.withJSON("data", jsonMessages); // 데이터 추가
        logger.info("Raw SPCS LLM chat completion: " + JSON.pretty(ob.get())); // 로깅: 원시 채팅 완료 데이터
        String endpoint = llmEndpointUrl + "/predict"; // 예측 엔드포인트 설정
        logger.info("posting to SPCS LLM at: " + endpoint); // 로깅: 엔드포인트
        JsonObject response = llmClient.postObjectToJSON("/predict", networkSettings.queryTimeoutMS, JsonObject.class, ob.get()); // 예측 요청 전송
        JsonArray generations = response.get("data").getAsJsonArray(); // 응답 데이터에서 생성 결과 가져오기
        if (generations.size() != 1) { // 생성 결과가 1개가 아닌 경우 예외 처리
            throw new IllegalArgumentException("Did not get a single generation??");
        }
        JsonArray generation0 = generations.get(0).getAsJsonArray(); // 첫 번째 생성 결과 가져오기
        JsonObject generation1 = generation0.get(1).getAsJsonObject(); // 두 번째 생성 결과 가져오기

        // SPCS는 문자열 배열을 반환하므로 JSON 문자열을 추출하고 파싱
        String text = generation1.get("outputs").getAsString(); // 생성된 텍스트 가져오기
        JsonArray gen3 = gson.fromJson(text, JsonArray.class); // JSON 문자열을 배열로 변환
        String genText = gen3.get(0).getAsJsonObject().get("generated_text").getAsString(); // 생성된 텍스트 추출

        // 최종 결과 구성
        SimpleCompletionResponse completionResponse = new SimpleCompletionResponse(); // 단순 완료 응답 객체 생성
        completionResponse.text = genText; // 생성된 텍스트 설정
        completionResponse.promptTokens = (int)(2.5f * new TokenizedText(completePrompt).size()); // 프롬프트 토큰 수 설정
        completionResponse.completionTokens = (int)(2.5f * new TokenizedText(genText).size()); // 완료 토큰 수 설정
        completionResponse.tokenCountsAreEstimated = true; // 토큰 수가 추정됨을 표시

        return completionResponse; // 완료 응답 반환
    }

    public SimpleEmbeddingResponse embed(String text) throws IOException { // 임베딩 메서드
        ObjectBuilder ob = JF.obj(); // JSON 객체 빌더 생성
        JsonArray jsonMessages = new JsonArray(); // 메시지 JSON 배열 생성
        JsonArray jsonMessage = new JsonArray(); // 개별 메시지 JSON 배열 생성
        jsonMessage.add(Integer.valueOf(0)); // 인덱스 추가
        JsonObject jo = JF.obj().withJSON("inputs", text).get(); // 입력 텍스트 추가
        jsonMessage.add(jo); // 개별 메시지에 추가
        jsonMessages.add(jsonMessage); // 메시지 배열에 추가

        ob.withJSON("data", jsonMessages); // 데이터 추가
        logger.info("Raw SPCS LLM embed: " + JSON.pretty(ob.get())); // 로깅: 원시 임베딩 데이터

        String endpoint = llmEndpointUrl + "/predict"; // 예측 엔드포인트 설정
        logger.info("posting to SPCS LLM at: " + endpoint); // 로깅: 엔드포인트

        JsonObject response = llmClient.postObjectToJSON("/predict", networkSettings.queryTimeoutMS, JsonObject.class, ob.get()); // 예측 요청 전송

        JsonArray generations = response.get("data").getAsJsonArray(); // 응답 데이터에서 생성 결과 가져오기

        if (generations.size() != 1) { // 생성 결과가 1개가 아닌 경우 예외 처리
            throw new IllegalArgumentException("Did not get a single generation??");
        }

        JsonArray generation0 = generations.get(0).getAsJsonArray(); // 첫 번째 생성 결과 가져오기
        JsonObject generation1 = generation0.get(1).getAsJsonObject(); // 두 번째 생성 결과 가져오기
        String embedArrayStr = generation1.get("outputs").getAsString(); // 임베딩 배열 문자열 가져오기
        JsonArray embedArray = JSON.parse(embedArrayStr, JsonArray.class); // JSON 문자열을 배열로 변환
        double[] embeds = new double[embedArray.size()]; // 임베딩 배열 초기화
        Iterator<JsonElement> arrayIT = embedArray.iterator(); // 배열 이터레이터 생성
        int i = 0; // 인덱스 초기화
        while (arrayIT.hasNext()) { // 배열을 반복
            embeds[i] = arrayIT.next().getAsDouble(); // 이터레이터에서 값 가져오기
            i++; // 인덱스 증가
        }
        SimpleEmbeddingResponse ret = new SimpleEmbeddingResponse(); // 단순 임베딩 응답 객체 생성
        ret.embedding = embeds; // 임베딩 설정
        ret.promptTokens = (int)(2.5f * new TokenizedText(text).size()); // 프롬프트 토큰 수 설정
        ret.tokenCountsAreEstimated = true; // 토큰 수가 추정됨을 표시

        return ret; // 임베딩 응답 반환
    }

    public List<SimpleEmbeddingResponse> embedBatch(List<EmbeddingQuery> queries) throws IOException { // 배치 임베딩 메서드
        List<SimpleEmbeddingResponse> ret = new ArrayList<>(); // 응답 리스트 생성

        for (EmbeddingQuery query : queries) { // 각 임베딩 쿼리에 대해 반복
            long before = System.currentTimeMillis(); // 시작 시간 기록
            SimpleEmbeddingResponse scr = null; // 단순 임베딩 응답 초기화

            logger.info("SPCS LLM Complete: " + JSON.json(query)); // 로깅: 임베딩 쿼리
            scr = embed(query.text); // 임베딩 메서드 호출

            usageData.totalComputationTimeMS += (System.currentTimeMillis() - before); // 총 계산 시간 갱신
            double estimatedCostUSD = spcsCreditsPerHourCost * snowflakeCreditCost * usageData.totalComputationTimeMS / 3600000; // 예상 비용 계산
            usageData.estimatedCostUSD += estimatedCostUSD; // 총 비용 갱신
            scr.estimatedCost = estimatedCostUSD; // 임베딩 응답에 예상 비용 추가
            usageData.totalPromptTokens += scr.promptTokens; // 총 프롬프트 토큰 갱신

            ret.add(scr); // 응답 리스트에 추가
        }
        return ret; // 응답 리스트 반환
    }

    //@Override // 주석 처리된 오버라이드 주석
    public ComputeResourceUsage getTotalCRU(LLMUsageType usageType, LLMStructuredRef llmRef) { // 총 리소스 사용량 메서드
        ComputeResourceUsage cru = new ComputeResourceUsage(); // 리소스 사용량 객체 생성
        cru.setupLLMUsage(usageType, llmRef.connection, llmRef.type.toString()); // 리소스 사용량 설정
        cru.llmUsage.setFromInternal(this.usageData); // 내부 사용량 데이터 설정
        return cru; // 리소스 사용량 반환
    }

    private static DKULogger logger = DKULogger.getLogger("dku.llm.spcsplugin"); // 로거 초기화
}