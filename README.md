# Snowflake Snowpark Container Services LLM 플러그인

이 플러그인을 사용하면 Snowpark Container Services (SPCS)에 호스팅된 LLM을 LLM Mesh의 일부로 활용할 수 있습니다.

![Screenshot 2024-02-09 at 4 58 15 PM](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/155dc4dd-148f-484a-a6c5-6643a52b2b2c)

# 기능

- 사용자 정의 LLM 연결 옵션
- SPCS에 호스팅된 채팅 완료 엔드포인트에 연결하여 Dataiku Prompt Studios, LLM 레시피 및 LLM Mesh Python API에서 사용할 수 있습니다.
- SPCS에 호스팅된 임베딩 엔드포인트에 연결하여 Dataiku Embed 레시피에서 Retrieval Augmented Generation (RAG)에 사용할 수 있습니다.

# 제한 사항

- Dataiku 버전 12.5.2 이상을 사용해야 합니다.
- SPCS에 연결하기 위해 Snowflake OAUTH를 사용해야 합니다.
- SPCS에 LLM을 배포하고 유지 관리해야 합니다 (도움을 주기 위한 샘플 코드가 아래에 있습니다).

# 설정
## Snowflake 측 리소스 설정
1. Snowflake 계정에서 Snowpark Container Services (SPCS 작업 포함)가 활성화되어 있는지 확인합니다 (Snowflake 담당자에게 문의).

2. (Snowflake에서 ACCOUNTADMIN으로) 아래 코드를 실행하여:
   a. SPCS에 대한 접근 권한이 있는 역할을 생성하고 이 역할을 사용자에게 부여합니다.
   b. 메인 LLM과 임베딩 LLM을 위한 두 개의 SPCS 컴퓨트 풀을 생성합니다.
   c. 간단한 관리 쿼리를 실행할 웨어하우스를 생성합니다 (또는 기존 웨어하우스에 DATAIKU_SPCS_ROLE에 대한 접근 권한을 부여할 수 있습니다).
   d. LLM 모델 레지스트리를 보관할 데이터베이스와 스키마를 생성합니다.
   e. Snowflake OAUTH 보안 통합을 생성합니다.

``` sql
-- Snowflake SQL 지침의 시작 --
-- ROLE ACCOUNTADMIN을 사용하여 역할과 컴퓨트 풀을 생성해야 합니다.
USE ROLE ACCOUNTADMIN;

-- 네트워크 규칙과 외부 접근 통합을 생성합니다. 이를 위해 전용 DB/스키마를 생성합니다.
CREATE DATABASE NETWORK_DB;  -- 네트워크 관련 정보를 저장할 데이터베이스 생성
CREATE SCHEMA NETWORK_SCHEMA;  -- 데이터베이스 내에 스키마 생성

-- EGRESS 모드의 네트워크 규칙을 생성합니다.
CREATE OR REPLACE NETWORK RULE SNOWFLAKE_EGRESS_ACCESS   
   MODE = EGRESS   -- EGRESS 모드 설정
   TYPE = HOST_PORT   -- 호스트 포트 유형 지정
   VALUE_LIST = ('0.0.0.0:443','0.0.0.0:80');  -- 허용할 IP와 포트 목록

-- 외부 접근 통합을 생성합니다.
CREATE EXTERNAL ACCESS INTEGRATION SNOWFLAKE_EGRESS_ACCESS_INTEGRATION   
   ALLOWED_NETWORK_RULES = (SNOWFLAKE_EGRESS_ACCESS)   -- 사용할 네트워크 규칙 지정
   ENABLED = true;  -- 통합 활성화

-- OAUTH 통합을 생성합니다.
CREATE SECURITY INTEGRATION OAUTH_DATAIKU_SPCS   
   TYPE = OAUTH   -- OAUTH 유형 지정
   ENABLED = TRUE   -- 활성화
   OAUTH_CLIENT = CUSTOM   -- 사용자 정의 클라이언트
   OAUTH_CLIENT_TYPE = 'CONFIDENTIAL'   -- 비공식 클라이언트 유형
   OAUTH_REDIRECT_URI = 'https://<YOUR_DATAIKU_INSTANCE_URL>/dip/api/oauth2-callback'  -- 리다이렉트 URI 설정
   OAUTH_ISSUE_REFRESH_TOKENS = TRUE   -- 리프레시 토큰 발급 허용
   OAUTH_REFRESH_TOKEN_VALIDITY = 7776000   -- 리프레시 토큰 유효 기간(초)
   BLOCKED_ROLES_LIST = ('SYSADMIN');  -- 블록된 역할 목록

-- OAUTH_CLIENT_ID와 OAUTH_CLIENT_SECRET을 가져옵니다.
CALL SYSTEM$SHOW_OAUTH_CLIENT_SECRETS('OAUTH_DATAIKU_SPCS');

-- Dataiku 사용자를 위한 LLM 호출 역할을 생성합니다.
CREATE ROLE DATAIKU_SPCS_ROLE;  
GRANT ROLE DATAIKU_SPCS_ROLE TO USER "<SNOWFLAKE_USER_TO_DEPLOY_AND_USE_MODELS>";  -- 특정 사용자에게 역할 부여

-- 새로운 역할에 외부 접근 통합 사용 권한을 부여합니다.
GRANT USAGE ON INTEGRATION SNOWFLAKE_EGRESS_ACCESS_INTEGRATION TO ROLE DATAIKU_SPCS_ROLE;

-- LLM을 위한 GPU 리소스를 호스팅할 컴퓨트 풀을 생성합니다. GPU_3는 가장 작은 유형입니다.
CREATE COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL with instance_family=GPU_NV_S min_nodes=1 max_nodes=1;  
GRANT USAGE ON COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;  -- 역할에 사용 권한 부여
GRANT MONITOR ON COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;  -- 모니터링 권한 부여

GRANT BIND SERVICE ENDPOINT on ACCOUNT to DATAIKU_SPCS_ROLE;  -- 서비스 엔드포인트 바인딩 권한 부여

-- LLM을 위한 CPU 리소스를 호스팅할 컴퓨트 풀을 생성합니다. STANDARD_1은 가장 작은 유형입니다.
CREATE COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL with instance_family=CPU_X64_XS min_nodes=1 max_nodes=1;  
GRANT USAGE ON COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;  -- 역할에 사용 권한 부여
GRANT MONITOR ON COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL to role DATAIKU_SPCS_ROLE;  -- 모니터링 권한 부여

-- Dataiku에 사용할 웨어하우스를 생성합니다.
CREATE OR REPLACE WAREHOUSE DATAIKU_SPCS_WAREHOUSE WITH   
   WAREHOUSE_SIZE='X-SMALL'   -- 웨어하우스 크기 설정
   AUTO_SUSPEND = 60   -- 자동 일시 중지 시간(분)
   AUTO_RESUME = true   -- 자동 재개 활성화
   INITIALLY_SUSPENDED=false;  -- 처음부터 일시 중지하지 않음

GRANT ALL ON WAREHOUSE DATAIKU_SPCS_WAREHOUSE TO ROLE DATAIKU_SPCS_ROLE;  -- 역할에 모든 권한 부여

-- Dataiku에 사용할 데이터베이스를 생성합니다.
CREATE DATABASE DATAIKU_SPCS;  
GRANT OWNERSHIP ON DATABASE DATAIKU_SPCS TO ROLE DATAIKU_SPCS_ROLE;  -- 역할에 소유권 부여

-- 생성한 역할, 데이터베이스, 웨어하우스를 사용합니다.
USE ROLE DATAIKU_SPCS_ROLE;  
USE DATABASE DATAIKU_SPCS;  
USE WAREHOUSE DATAIKU_SPCS_WAREHOUSE;  

-- 모델 레지스트리를 위한 스키마를 생성합니다.
CREATE SCHEMA MODEL_REGISTRY;  
USE SCHEMA MODEL_REGISTRY;  
-- Snowflake SQL 지침의 끝
```

## Dataiku에서 플러그인 설정하기
1. 플러그인 설치  - Plugins -> Add Plugin -> Fetch from Git repository로 이동한 다음, 이 저장소 URL을 입력합니다:  

![add_plugin_from_git_repo](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/72b5ec9a-e037-4d83-a6b4-e8fbf16018d5)  

![clone_plugin](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/a33f9925-6baf-4476-8695-2954056cf5b4)  

2. 플러그인 설정에서 "Snowflake login with SSO" 사전 설정 추가  

![spcs_plugin_oauth_params](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/938ba63f-6713-4820-9462-9474f8ec6708)  

3. Admin -> Connections 페이지에서 "spcs-access-only"라는 새로운 Snowflake 연결 생성. Snowflake DB, Schema, Warehouse 및 Snowflake 측 설정에서 Oauth 정보를 입력합니다. "Auth authorization endpoint"는 다음과 같이 설정해야 합니다: https://<YOUR_SNOWFLAKE_ACCOUNT>.aws.snowflakecomputing.com/oauth/authorize 그리고 "Auth token endpoint"는 다음과 같아야 합니다: https://<YOUR_SNOWFLAKE_ACCOUNT>.aws.snowflakecomputing.com/oauth/token-request. 사용자가 이 연결에서 데이터셋을 생성하지 못하도록 "allow write"를 해제합니다. 우리는 이 연결을 SPCS에 모델을 배포하는 데만 사용할 것입니다. 자격 증명 모드를 "per user"로 변경합니다.

![spcs_access_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/4fd6fa52-9b02-4479-af03-9d95c5f285bb)  

![spcs_access_connection_2](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/b99d299b-fa7d-4612-948a-228d9fdb1010)  

4. 사용자 프로필 자격 증명으로 이동하여 "spcs-access-only" 연결과 "snowpark-container-services-llm" 플러그인에 대해 Oauth 인증 과정을 진행합니다  

![spcs_user_oauth](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/5e5dc06b-a4ab-49c0-b319-f4e283265d41)  
![spcs_user_oauth_2](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/c832f93b-d5dc-498f-9916-e875c94e1217)  


5. Python 3.8 코드 환경을 생성합니다 (저는 "py_38_snowpark_llms"라고 이름 지었습니다) 그리고 다음 패키지를 추가합니다:
```
scikit-learn==1.2.1
mlflow==1.30.0
statsmodels==0.12.2
protobuf==3.16.0
xgboost==1.7.3
lightgbm==3.3.5
matplotlib==3.7.4
scipy==1.10.1
snowflake-snowpark-python==1.12.1
snowflake-snowpark-python[pandas]
snowflake-connector-python[pandas]
MarkupSafe<2.1.0
cloudpickle==2.0.0
flask>=1.0,<1.1
itsdangerous<2.1.0
Jinja2>=2.11,<2.12
snowflake-ml-python==1.3.0
dash==2.15.0
dash_bootstrap_components==1.5.0
transformers==4.37.2
sentence-transformers==2.3.1
datasets==2.16.1
torch
sentencepiece==0.1.99
presidio-anonymizer==2.2.352
presidio_analyzer==2.2.352
spacy==3.7.3
langchain==0.0.347
bitsandbytes>0.37.0
```

6. LLM을 SPCS에 배포한 후, 채팅 완료 및 임베딩 모델에 대한 결과 공개 엔드포인트를 검색합니다. 배포 방법을 보여주는 샘플 노트북이 있습니다 [여기](Deploy_LLMs_to_Snowpark_Container_Services.ipynb). 채팅 완료를 위해: Zephyr 7B-beta, Llama2, Phi2, Falcon을 배포하며, 텍스트 임베딩을 위해: MiniLM-L6-v2를 배포합니다. 

URL은 다음과 같이 보여야 합니다: `https://{ENDPOINT_ID}-{SNOWFLAKE_ACCOUNT_NAME/ACCOUNT_ID}.snowflakecomputing.app`

7. 새로운 "Custom LLM" 연결을 생성한 후, "Snowpark Container Services LLM" 플러그인을 선택합니다.  

![create_spcs_llm_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/26b9b008-c4ab-4909-afc3-5f7717e56dc1)  

주요 LLM에 대한 모델을 추가하고, 이름을 지정합니다. "Chat completion" 기능을 선택하고, "Snowpark Container Services LLM" 유형을 선택합니다. 이전에 생성한 LLM 엔드포인트 URL을 입력하고, Snowflake 계정 URL, 컴퓨트 풀 크레딧 비용, Snowflake 크레딧 비용(이 비용은 Snowflake 담당자에게 문의)도 입력합니다. 쿼리의 최대 병렬 처리는 사용자에게 달려 있습니다. 1 또는 2로 시작하는 것이 좋습니다. 

![chat_completion_spcs_model_in_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/ed61fbcb-cecd-4c88-b7ca-80922321eab7)  

8. 텍스트 임베딩 모델에 대한 또 다른 모델을 이 동일한 연결에 추가합니다. 이름을 지정하고, "Text embedding" 기능을 선택하며, "Snowpark Container Services LLM" 유형을 선택합니다. 이전에 생성한 임베딩 모델의 LLM 엔드포인트 URL을 입력합니다. 이 모델은 Snowflake 측에서 설정한 내용에 따라 다른 컴퓨트 풀 비용이 발생할 수 있습니다.

![text_embedding_spcs_model_in_connection](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/c0ba3669-e4a6-473b-8f76-f91f0af691e7)  

10. 이제 채팅 완료 LLM을 LLM Mesh에서 사용할 준비가 되었습니다!!  

![spcs_chat_completion_model_prompt_studio](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/075a49b9-d8ed-4814-96ef-23b04b9aec54)  

12. 임베딩 LLM도 Retrieval Augmented Generation (RAG)을 위해 사용할 수 있습니다!!  

![spcs_text_embedding_model_knowledge_bank](https://github.com/dataiku/dss-plugin-snowpark-container-services-llm/assets/22987725/631fe96c-616f-4ea3-8c41-ff2c9ee5b98a)  

14. 다음은 Snowflake에서 LLM 서비스와 컴퓨트 풀을 삭제하기 위해 실행할 수 있는 샘플 SQL 코드입니다.

```sql
-- 서비스는 컴퓨트 풀을 삭제하기 전에 삭제해야 합니다. 현재 Snowflake에서 사용 가능한 서비스 목록을 표시합니다. 이 단계에서 삭제할 서비스의 ID를 확인합니다.
SHOW SERVICES; 
DROP SERVICE <SERVICE_ID_FROM_ABOVE_RESULTS>; -- 위에서 확인한 서비스 ID를 사용하여 해당 서비스를 삭제합니다. 서비스가 존재하지 않으면 이 명령이 실패합니다.

DROP COMPUTE POOL DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL; -- "DATAIKU_GPU_NV_S_MODEL_COMPUTE_POOL"라는 이름의 GPU 컴퓨트 풀을 삭제합니다. 이 풀은 LLM의 채팅 완료 모델을 지원하는 데 사용되었습니다.
DROP COMPUTE POOL DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL; -- "DATAIKU_CPU_X64_XS_EMBED_COMPUTE_POOL"라는 이름의 CPU 컴퓨트 풀을 삭제합니다. 이 풀은 텍스트 임베딩 모델을 지원하는 데 사용되었습니다.
```
